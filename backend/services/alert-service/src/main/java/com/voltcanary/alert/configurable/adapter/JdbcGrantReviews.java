package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.alert.configurable.domain.AutomationGrant;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.alert.configurable.domain.GrantReview;
import com.voltcanary.alert.configurable.port.GrantReviewStore;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Durable reviewed permission creation. APPLYING is committed before the grant transaction. */
@Component
public class JdbcGrantReviews implements GrantReviewStore {
    private final GrantSql sql;
    public JdbcGrantReviews(GrantSql sql) { this.sql = sql; }

    @Override
    public GrantReview reserve(UUID account, UUID actor, UUID ruleReviewId, String key) {
        String reservationDigest = VclCanonical.sha256("AUTOMATION_GRANT/1:" + ruleReviewId);
        return sql.transaction(account, connection -> {
            try (var query = connection.prepareStatement("SELECT * FROM cfg_grant_review WHERE account_id=? AND reservation_key_hash=?")) {
                query.setObject(1, account);
                query.setString(2, VclCanonical.sha256(key));
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        var existing = GrantSql.review(rows);
                        require(actor.equals(existing.actorId()) && key.equals(existing.reservationKey())
                                && reservationDigest.equals(rows.getString("reservation_digest")), "IDEMPOTENCY_KEY_REUSED");
                        return existing;
                    }
                }
            }
            UUID ruleId;
            OffsetDateTime lease;
            try (var query = connection.prepareStatement("SELECT rule_id,expires_at FROM cfg_rule_review_reservation WHERE id=? AND account_id=? AND actor_id=? AND status='RESERVED'")) {
                query.setObject(1, ruleReviewId);
                query.setObject(2, account);
                query.setObject(3, actor);
                try (var rows = query.executeQuery()) {
                    require(rows.next(), "REVIEW_UNAVAILABLE");
                    ruleId = rows.getObject(1, UUID.class);
                    lease = rows.getObject(2, OffsetDateTime.class);
                    require(GrantSql.now(connection).isBefore(lease.toInstant()), "REVIEW_EXPIRED");
                }
            }
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_grant_review WHERE account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')")) {
                query.setObject(1, account);
                try (var rows = query.executeQuery()) { rows.next(); require(rows.getLong(1) < 100, "REVIEW_QUOTA"); }
            }
            UUID id = UUID.randomUUID();
            GrantSql.execute(connection, """
                    INSERT INTO cfg_grant_review(id,account_id,actor_id,rule_review_id,rule_id,grant_id,receipt_id,
                    reservation_key_hash,reservation_key,reservation_digest,status,expires_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'RESERVED',?)
                    """, id, account, actor, ruleReviewId, ruleId, UUID.randomUUID(), UUID.randomUUID(), VclCanonical.sha256(key), key, reservationDigest, lease);
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public GrantReview inspect(UUID account, UUID actor, UUID id) {
        return sql.transaction(account, connection -> sql.review(connection, account, actor, id));
    }

    @Override
    public GrantReview prepare(UUID account, UUID actor, UUID id, AutomationGrantScope scope, JsonNode pins) {
        return sql.transaction(account, connection -> {
            var review = sql.review(connection, account, actor, id);
            require(List.of("RESERVED", "PREPARED").contains(review.status()), review.status().equals("EXPIRED") ? "REVIEW_EXPIRED" : "REVIEW_STATE_CONFLICT");
            var now = GrantSql.now(connection);
            require(now.isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            require(now.isBefore(scope.expiresAt()) && !scope.expiresAt().isAfter(now.plus(Duration.ofDays(30))), "GRANT_EXPIRY_INVALID");
            String definition = VclCanonical.encode(scope.json());
            String readPins = VclCanonical.encode(pins);
            long revision = review.canonicalDefinition() == null || definition.equals(review.canonicalDefinition()) && readPins.equals(review.canonicalPins())
                    ? review.revision() : Math.addExact(review.revision(), 1);
            var envelope = JsonNodeFactory.instance.objectNode().put("profile", AutomationGrantScope.PROFILE)
                    .put("reviewId", id.toString()).put("accountId", account.toString()).put("actorId", actor.toString())
                    .put("grantId", review.grantId().toString()).put("ruleId", review.ruleId().toString())
                    .put("receiptId", review.receiptId().toString()).put("revision", revision).put("expiresAt", review.expiresAt().toString())
                    .put("definitionDigest", VclCanonical.sha256(definition)).put("pinsDigest", VclCanonical.sha256(readPins));
            String digest = VclCanonical.sha256(VclCanonical.encode(envelope));
            GrantSql.execute(connection, "UPDATE cfg_grant_review SET status='PREPARED',revision=?,canonical_definition=?,canonical_pins=?,review_digest=? WHERE id=?",
                    revision, definition, readPins, digest, id);
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public GrantReview begin(UUID account, UUID actor, UUID id, long revision, String digest, String key) {
        return sql.transaction(account, connection -> {
            var review = sql.review(connection, account, actor, id);
            require(review.revision() == revision && Objects.equals(review.reviewDigest(), digest), "REVIEW_CONFLICT");
            require(review.reservationKey().equals(key), "IDEMPOTENCY_KEY_REUSED");
            if (review.status().equals("SUCCEEDED")) return review;
            require(List.of("PREPARED", "APPLYING", "UNKNOWN").contains(review.status()), review.status().equals("EXPIRED") ? "REVIEW_EXPIRED" : "REVIEW_STATE_CONFLICT");
            var now = GrantSql.now(connection);
            var scope = AutomationGrantScope.parse(GrantJson.read(review.canonicalDefinition()));
            require(now.isBefore(review.expiresAt()) && now.isBefore(scope.expiresAt()), "REVIEW_EXPIRED");
            GrantSql.execute(connection, "UPDATE cfg_grant_review SET status='APPLYING',outcome_code=NULL WHERE id=?", id);
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public GrantReview complete(UUID account, UUID actor, UUID id) {
        return sql.transaction(account, connection -> {
            var review = sql.review(connection, account, actor, id);
            if (review.status().equals("SUCCEEDED")) return review;
            require(review.status().equals("APPLYING"), "REVIEW_STATE_CONFLICT");
            var now = GrantSql.now(connection);
            var scope = AutomationGrantScope.parse(GrantJson.read(review.canonicalDefinition()));
            require(now.isBefore(review.expiresAt()) && now.isBefore(scope.expiresAt()), "REVIEW_EXPIRED");
            GrantSql.execute(connection, """
                    INSERT INTO cfg_automation_grant(id,account_id,actor_id,rule_id,review_id,canonical_definition,canonical_pins,
                    definition_digest,epoch,status,expires_at) VALUES(?,?,?,?,?,?,?,?,1,'ACTIVE',?)
                    """, review.grantId(), account, actor, review.ruleId(), id, review.canonicalDefinition(), review.canonicalPins(),
                    VclCanonical.sha256(review.canonicalDefinition()), OffsetDateTime.ofInstant(scope.expiresAt(), ZoneOffset.UTC));
            GrantSql.execute(connection, "UPDATE cfg_grant_review SET status='SUCCEEDED',outcome_code=NULL WHERE id=?", id);
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public GrantReview unknown(UUID account, UUID actor, UUID id, String code) {
        return sql.transaction(account, connection -> {
            sql.review(connection, account, actor, id);
            GrantSql.execute(connection, "UPDATE cfg_grant_review SET status='UNKNOWN',outcome_code=? WHERE id=? AND status='APPLYING'", code, id);
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public AutomationGrant grant(UUID account, UUID actor, UUID id) {
        return sql.transaction(account, connection -> {
            var grant = sql.grant(connection, actor, id);
            require(grant.accountId().equals(account), "GRANT_UNAVAILABLE");
            require(grant.definitionDigest().equals(VclCanonical.sha256(grant.canonicalDefinition())), "GRANT_STATE_CORRUPT");
            return grant;
        });
    }

    @Override
    public GrantReview reconcile(UUID account, UUID actor, UUID id) {
        return sql.transaction(account, connection -> {
            var review = sql.review(connection, account, actor, id);
            if (!List.of("APPLYING", "UNKNOWN").contains(review.status())) return review;
            // Every possible grant insert takes this account/review fence and checks the same lease.
            // After expiry and absence under the fence, there is no remaining authorized insert.
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_automation_grant WHERE review_id=?")) {
                query.setObject(1, id);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    var now = GrantSql.now(connection);
                    var scope = AutomationGrantScope.parse(GrantJson.read(review.canonicalDefinition()));
                    if (rows.getLong(1) == 0 && (!now.isBefore(review.expiresAt()) || !now.isBefore(scope.expiresAt()))) {
                        String code = !now.isBefore(review.expiresAt()) ? "LEASE_EXPIRED_NO_EFFECT" : "GRANT_EXPIRED_NO_EFFECT";
                        GrantSql.execute(connection, "UPDATE cfg_grant_review SET status='FAILED',outcome_code=? WHERE id=?", code, id);
                    }
                }
            }
            return sql.review(connection, account, actor, id);
        });
    }

    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
}
