package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.alert.configurable.domain.EvidencePolicyDefinition;
import com.voltcanary.alert.configurable.domain.EvidencePolicyReview;
import com.voltcanary.alert.configurable.port.EvidencePolicyStore;
import com.voltcanary.vcl.VclCanonical;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import static com.voltcanary.alert.configurable.adapter.EvidencePolicySql.require;

/** Durable reviewed metadata creation; no evidence, alert, retention job or delivery effects. */
@Component
public class JdbcEvidencePolicies implements EvidencePolicyStore {
    private final EvidencePolicySql sql;
    public JdbcEvidencePolicies(EvidencePolicySql sql) { this.sql = sql; }

    @Override
    public java.util.Optional<EvidencePolicyReview> reservation(UUID account, UUID actor, UUID grantId, long epoch, String key) {
        return sql.transaction(account, connection -> find(connection, account, actor, grantId, epoch, key));
    }

    @Override
    public EvidencePolicyReview reserve(UUID account, UUID actor, UUID grantId, long epoch, String key) {
        return sql.transaction(account, connection -> {
            var replay = find(connection, account, actor, grantId, epoch, key);
            if (replay.isPresent()) return replay.get();
            var grant = sql.grant(connection, account, actor, grantId);
            require(grant.status().equals("ACTIVE"), "AUTOMATION_GRANT_INACTIVE");
            require(grant.epoch() == epoch, "GRANT_EPOCH_CONFLICT");
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_evidence_policy_review WHERE account_id=? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')")) {
                query.setObject(1, account);
                try (var rows = query.executeQuery()) { rows.next(); require(rows.getLong(1) < 100, "REVIEW_QUOTA"); }
            }
            var lease = GrantSql.now(connection).plus(Duration.ofMinutes(15));
            if (lease.isAfter(grant.expiresAt())) lease = grant.expiresAt();
            UUID id = UUID.randomUUID();
            GrantSql.execute(connection, """
                    INSERT INTO cfg_evidence_policy_review(id,account_id,actor_id,grant_id,rule_id,grant_epoch,
                    grant_definition_digest,policy_id,receipt_id,key_hash,reservation_key,status,expires_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,'RESERVED',?)
                    """, id, account, actor, grantId, grant.ruleId(), epoch, grant.definitionDigest(), UUID.randomUUID(), UUID.randomUUID(),
                    VclCanonical.sha256(key), key, OffsetDateTime.ofInstant(lease, ZoneOffset.UTC));
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public EvidencePolicyReview inspect(UUID account, UUID actor, UUID id) {
        return sql.transaction(account, connection -> sql.review(connection, account, actor, id));
    }

    @Override
    public EvidencePolicyReview prepare(UUID account, UUID actor, UUID id, EvidencePolicyDefinition definition) {
        return sql.transaction(account, connection -> {
            var review = sql.review(connection, account, actor, id);
            require(List.of("RESERVED", "PREPARED").contains(review.status()), review.status().equals("EXPIRED") ? "REVIEW_EXPIRED" : "REVIEW_STATE_CONFLICT");
            sql.usable(connection, review);
            require(GrantSql.now(connection).isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            String canonical = VclCanonical.encode(definition.json());
            long revision = review.canonicalDefinition() == null || canonical.equals(review.canonicalDefinition())
                    ? review.revision() : Math.addExact(review.revision(), 1);
            var envelope = JsonNodeFactory.instance.objectNode().put("profile", EvidencePolicyDefinition.PROFILE)
                    .put("reviewId", id.toString()).put("accountId", account.toString()).put("actorId", actor.toString())
                    .put("grantId", review.grantId().toString()).put("ruleId", review.ruleId().toString()).put("grantEpoch", Long.toString(review.grantEpoch()))
                    .put("grantDefinitionDigest", review.grantDefinitionDigest()).put("policyId", review.policyId().toString())
                    .put("receiptId", review.receiptId().toString()).put("revision", revision).put("expiresAt", review.expiresAt().toString())
                    .put("definitionDigest", VclCanonical.sha256(canonical));
            String digest = VclCanonical.sha256(VclCanonical.encode(envelope));
            GrantSql.execute(connection, "UPDATE cfg_evidence_policy_review SET status='PREPARED',revision=?,canonical_definition=?,review_digest=? WHERE id=?",
                    revision, canonical, digest, id);
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public EvidencePolicyReview begin(UUID account, UUID actor, UUID id, long revision, String digest, String key) {
        return sql.transaction(account, connection -> {
            var review = sql.review(connection, account, actor, id);
            require(review.revision() == revision && Objects.equals(review.reviewDigest(), digest), "REVIEW_CONFLICT");
            require(review.reservationKey().equals(key), "IDEMPOTENCY_KEY_REUSED");
            if (review.status().equals("SUCCEEDED")) return review;
            require(List.of("PREPARED", "APPLYING", "UNKNOWN").contains(review.status()), review.status().equals("EXPIRED") ? "REVIEW_EXPIRED" : "REVIEW_STATE_CONFLICT");
            sql.usable(connection, review);
            require(GrantSql.now(connection).isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            GrantSql.execute(connection, "UPDATE cfg_evidence_policy_review SET status='APPLYING',outcome_code=NULL WHERE id=?", id);
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public EvidencePolicyReview complete(UUID account, UUID actor, UUID id) {
        return sql.transaction(account, connection -> {
            var review = sql.review(connection, account, actor, id);
            if (review.status().equals("SUCCEEDED")) return review;
            require(review.status().equals("APPLYING"), "REVIEW_STATE_CONFLICT");
            var grant = sql.usable(connection, review);
            require(GrantSql.now(connection).isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            EvidencePolicyDefinition.parse(GrantJson.read(review.canonicalDefinition()));
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_evidence_policy WHERE account_id=?")) {
                query.setObject(1, account);
                try (var rows = query.executeQuery()) { rows.next(); require(rows.getLong(1) < 1000, "EVIDENCE_POLICY_QUOTA"); }
            }
            GrantSql.execute(connection, """
                    INSERT INTO cfg_evidence_policy(id,account_id,actor_id,rule_id,grant_id,grant_epoch,grant_definition_digest,
                    review_id,canonical_definition,definition_digest,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, review.policyId(), account, actor, review.ruleId(), review.grantId(), review.grantEpoch(), review.grantDefinitionDigest(),
                    id, review.canonicalDefinition(), VclCanonical.sha256(review.canonicalDefinition()), OffsetDateTime.ofInstant(grant.expiresAt(), ZoneOffset.UTC));
            GrantSql.execute(connection, "UPDATE cfg_evidence_policy_review SET status='SUCCEEDED',outcome_code=NULL WHERE id=?", id);
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public EvidencePolicyReview unknown(UUID account, UUID actor, UUID id, String code) {
        return sql.transaction(account, connection -> {
            sql.review(connection, account, actor, id);
            GrantSql.execute(connection, "UPDATE cfg_evidence_policy_review SET status='UNKNOWN',outcome_code=? WHERE id=? AND status='APPLYING'", code, id);
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public EvidencePolicyReview reconcile(UUID account, UUID actor, UUID id) {
        return sql.transaction(account, connection -> {
            var review = sql.review(connection, account, actor, id);
            if (!List.of("APPLYING", "UNKNOWN").contains(review.status())) return review;
            var grant = sql.grant(connection, account, actor, review.grantId());
            boolean grantChanged = !grant.status().equals("ACTIVE") || grant.epoch() != review.grantEpoch()
                    || !grant.definitionDigest().equals(review.grantDefinitionDigest());
            boolean expired = !GrantSql.now(connection).isBefore(review.expiresAt());
            if (grantChanged || expired) {
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_evidence_policy WHERE review_id=?")) {
                    query.setObject(1, id);
                    try (var rows = query.executeQuery()) {
                        rows.next();
                        if (rows.getLong(1) == 0) GrantSql.execute(connection,
                                "UPDATE cfg_evidence_policy_review SET status='FAILED',outcome_code=? WHERE id=?",
                                grantChanged ? "GRANT_CHANGED_NO_EFFECT" : "LEASE_EXPIRED_NO_EFFECT", id);
                    }
                }
            }
            return sql.review(connection, account, actor, id);
        });
    }

    @Override
    public Inspection policy(UUID account, UUID actor, UUID id) {
        return sql.transaction(account, connection -> {
            var policy = sql.policy(connection, account, actor, id);
            var grant = sql.grant(connection, account, actor, policy.grantId());
            boolean usable = grant.status().equals("ACTIVE") && grant.epoch() == policy.grantEpoch()
                    && grant.definitionDigest().equals(policy.grantDefinitionDigest()) && grant.ruleId().equals(policy.ruleId())
                    && GrantSql.now(connection).isBefore(policy.expiresAt());
            return new Inspection(policy, usable);
        });
    }

    private java.util.Optional<EvidencePolicyReview> find(java.sql.Connection connection, UUID account, UUID actor,
            UUID grantId, long epoch, String key) throws java.sql.SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_evidence_policy_review WHERE account_id=? AND key_hash=?")) {
            query.setObject(1, account);
            query.setString(2, VclCanonical.sha256(key));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return java.util.Optional.empty();
                var existing = EvidencePolicySql.read(rows);
                require(actor.equals(existing.actorId()) && grantId.equals(existing.grantId()) && epoch == existing.grantEpoch()
                        && key.equals(existing.reservationKey()), "IDEMPOTENCY_KEY_REUSED");
                return java.util.Optional.of(existing);
            }
        }
    }
}
