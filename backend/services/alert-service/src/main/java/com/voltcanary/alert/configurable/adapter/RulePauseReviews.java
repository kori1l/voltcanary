package com.voltcanary.alert.configurable.adapter;

import com.voltcanary.alert.configurable.port.RuleAccountAuthority;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclRulePauseIntent;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records owner consent for a stop operation without requiring a still-active execution grant. */
@Service
@RequiredArgsConstructor
public class RulePauseReviews {
    private final JdbcTemplate jdbc;
    private final RuleAccountAuthority accounts;

    public record Review(UUID id, UUID accountId, UUID actorId, VclRulePauseIntent intent, String reviewDigest,
            Instant expiresAt, Instant confirmedAt) { }

    @Transactional
    public Review prepare(UUID actor, VclRulePauseIntent intent) {
        UUID account = accounts.account(actor);
        return jdbc.execute((ConnectionCallback<Review>) connection -> {
            lockAccount(connection, account);
            verifyRuleOwner(connection, account, actor, intent);
            var now = OwnerDatabaseClock.now(connection);
            try (var query = connection.prepareStatement("""
                    SELECT * FROM cfg_rule_pause_review WHERE account_id=? AND actor_id=? AND expires_at>?
                    ORDER BY id FETCH FIRST 129 ROWS ONLY
                    """)) {
                query.setObject(1, account);
                query.setObject(2, actor);
                query.setObject(3, now.atOffset(ZoneOffset.UTC));
                try (var rows = query.executeQuery()) {
                    int active = 0;
                    while (rows.next()) {
                        var existing = read(rows);
                        if (existing.intent().equals(intent)) return existing;
                        require(++active < 128, "REVIEW_QUOTA");
                    }
                }
            }
            UUID id = UUID.randomUUID();
            var expiry = now.plusSeconds(900);
            String digest = intent.reviewDigest(account, actor, id);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO cfg_rule_pause_review(id,account_id,actor_id,rule_id,grant_id,deployment_epoch,
                        deployment_revision,bundle_digest,review_digest,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)
                    """)) {
                insert.setObject(1, id);
                insert.setObject(2, account);
                insert.setObject(3, actor);
                insert.setObject(4, intent.ruleId());
                insert.setObject(5, intent.grantId());
                insert.setObject(6, intent.epoch());
                insert.setLong(7, intent.revision());
                insert.setString(8, intent.bundleDigest());
                insert.setString(9, digest);
                insert.setObject(10, expiry.atOffset(ZoneOffset.UTC));
                insert.executeUpdate();
            }
            return new Review(id, account, actor, intent, digest, expiry, null);
        });
    }

    @Transactional
    public Review confirm(UUID actor, UUID id, String digest) {
        UUID account = accounts.account(actor);
        return jdbc.execute((ConnectionCallback<Review>) connection -> {
            lockAccount(connection, account);
            var review = find(connection, account, actor, id);
            require(review.reviewDigest().equals(digest), "REVIEW_CONFLICT");
            verifyRuleOwner(connection, account, actor, review.intent());
            Instant now = OwnerDatabaseClock.now(connection);
            require(now.isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            if (review.confirmedAt() != null) return review;
            try (var update = connection.prepareStatement("UPDATE cfg_rule_pause_review SET confirmed_at=? WHERE id=?")) {
                update.setObject(1, now.atOffset(ZoneOffset.UTC));
                update.setObject(2, id);
                update.executeUpdate();
            }
            return new Review(id, account, actor, review.intent(), digest, review.expiresAt(), now);
        });
    }

    @Transactional(readOnly = true)
    public Review confirmed(UUID actor, UUID id) {
        UUID account = accounts.account(actor);
        return jdbc.execute((ConnectionCallback<Review>) connection -> {
            var review = find(connection, account, actor, id);
            verifyRuleOwner(connection, account, actor, review.intent());
            require(OwnerDatabaseClock.now(connection).isBefore(review.expiresAt()), "REVIEW_EXPIRED");
            require(review.confirmedAt() != null, "REVIEW_STATE_CONFLICT");
            return review;
        });
    }

    private static void lockAccount(Connection connection, UUID account) throws SQLException {
        try (var query = connection.prepareStatement("SELECT account_id FROM cfg_rule_owner_fence WHERE account_id=? FOR UPDATE")) {
            query.setObject(1, account);
            try (var rows = query.executeQuery()) { require(rows.next(), "OWNER_ACCESS_REVOKED"); }
        }
    }

    private static void verifyRuleOwner(Connection connection, UUID account, UUID actor, VclRulePauseIntent intent)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT id FROM cfg_automation_grant WHERE id=? AND account_id=? AND actor_id=? AND rule_id=?
                """)) {
            query.setObject(1, intent.grantId());
            query.setObject(2, account);
            query.setObject(3, actor);
            query.setObject(4, intent.ruleId());
            try (var rows = query.executeQuery()) { require(rows.next(), "GRANT_UNAVAILABLE"); }
        }
    }

    private static Review find(Connection connection, UUID account, UUID actor, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_rule_pause_review WHERE id=? AND account_id=? AND actor_id=?")) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "REVIEW_UNAVAILABLE");
                return read(rows);
            }
        }
    }

    private static Review read(ResultSet rows) throws SQLException {
        UUID id = rows.getObject("id", UUID.class);
        UUID account = rows.getObject("account_id", UUID.class);
        UUID actor = rows.getObject("actor_id", UUID.class);
        var intent = new VclRulePauseIntent(rows.getObject("rule_id", UUID.class), rows.getObject("grant_id", UUID.class),
                rows.getObject("deployment_epoch", UUID.class), rows.getLong("deployment_revision"), rows.getString("bundle_digest"));
        String digest = rows.getString("review_digest");
        require(intent.reviewDigest(account, actor, id).equals(digest), "REVIEW_CONFLICT");
        var confirmed = rows.getObject("confirmed_at", OffsetDateTime.class);
        return new Review(id, account, actor, intent, digest, rows.getObject("expires_at", OffsetDateTime.class).toInstant(),
                confirmed == null ? null : confirmed.toInstant());
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
