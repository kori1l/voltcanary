package com.voltcanary.alert.configurable.adapter;

import com.voltcanary.alert.configurable.application.AutomationGrantReviews;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclRuleCutoverIntent;
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

/** Owns exact cutover consent. Analytics still owns execution and readiness. */
@Service
@RequiredArgsConstructor
public class RuleCutoverReviews {

    private final JdbcTemplate jdbc;
    private final AutomationGrantReviews grants;

    public record Review(
        UUID id,
        UUID accountId,
        UUID actorId,
        long grantEpoch,
        VclRuleCutoverIntent intent,
        String reviewDigest,
        Instant expiresAt,
        Instant confirmedAt
    ) {}

    @Transactional
    public Review prepare(UUID actor, String authorization, VclRuleCutoverIntent intent) {
        var grant = grants.grant(actor, authorization, intent.grantId());
        require(
            intent.ruleId().equals(grant.ruleId()) && "ACTIVE".equals(grant.status()),
            "AUTOMATION_GRANT_INACTIVE"
        );
        return jdbc.execute(
            (ConnectionCallback<Review>) connection -> {
                lockAccount(connection, grant.accountId());
                Instant now = OwnerDatabaseClock.now(connection);
                long epoch = Long.parseLong(grant.epoch());
                Instant grantExpiry = lockGrant(
                    connection,
                    grant.accountId(),
                    actor,
                    intent,
                    epoch,
                    now
                );
                try (
                    var query = connection.prepareStatement(
                        """
                        SELECT * FROM cfg_rule_cutover_review WHERE account_id=? AND successor_epoch=?
                        """
                    )
                ) {
                    query.setObject(1, grant.accountId());
                    query.setObject(2, intent.successorEpoch());
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) {
                            var existing = read(rows);
                            require(
                                existing.actorId().equals(actor) &&
                                    existing.intent().equals(intent) &&
                                    existing.grantEpoch() == epoch,
                                "REVIEW_CONFLICT"
                            );
                            require(now.isBefore(existing.expiresAt()), "REVIEW_EXPIRED");
                            return existing;
                        }
                    }
                }
                UUID id = UUID.randomUUID();
                String digest = intent.reviewDigest(grant.accountId(), actor, id, epoch);
                Instant expiry = now.plusSeconds(900).isBefore(grantExpiry)
                    ? now.plusSeconds(900)
                    : grantExpiry;
                try (
                    var insert = connection.prepareStatement(
                        """
                        INSERT INTO cfg_rule_cutover_review(id,account_id,actor_id,rule_id,grant_id,grant_epoch,
                            predecessor_epoch,successor_epoch,bundle_id,bundle_digest,checkpoint_digest,review_digest,expires_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """
                    )
                ) {
                    insert.setObject(1, id);
                    insert.setObject(2, grant.accountId());
                    insert.setObject(3, actor);
                    insert.setObject(4, intent.ruleId());
                    insert.setObject(5, intent.grantId());
                    insert.setLong(6, epoch);
                    insert.setObject(7, intent.predecessorEpoch());
                    insert.setObject(8, intent.successorEpoch());
                    insert.setObject(9, intent.bundleId());
                    insert.setString(10, intent.bundleDigest());
                    insert.setString(11, intent.checkpointDigest());
                    insert.setString(12, digest);
                    insert.setObject(13, expiry.atOffset(ZoneOffset.UTC));
                    insert.executeUpdate();
                }
                return new Review(
                    id,
                    grant.accountId(),
                    actor,
                    epoch,
                    intent,
                    digest,
                    expiry,
                    null
                );
            }
        );
    }

    @Transactional
    public Review confirm(UUID actor, String authorization, UUID id, String reviewDigest) {
        var observed = jdbc.execute(
            (ConnectionCallback<Review>) connection -> find(connection, actor, id)
        );
        require(observed.reviewDigest().equals(reviewDigest), "REVIEW_CONFLICT");
        var grant = grants.grant(actor, authorization, observed.intent().grantId());
        require(
            grant.accountId().equals(observed.accountId()) && "ACTIVE".equals(grant.status()),
            "AUTOMATION_GRANT_INACTIVE"
        );
        return jdbc.execute(
            (ConnectionCallback<Review>) connection -> {
                lockAccount(connection, observed.accountId());
                var current = find(connection, actor, id);
                require(current.reviewDigest().equals(reviewDigest), "REVIEW_CONFLICT");
                Instant now = OwnerDatabaseClock.now(connection);
                lockGrant(
                    connection,
                    current.accountId(),
                    actor,
                    current.intent(),
                    current.grantEpoch(),
                    now
                );
                require(now.isBefore(current.expiresAt()), "REVIEW_EXPIRED");
                if (current.confirmedAt() != null) return current;
                try (
                    var update = connection.prepareStatement(
                        "UPDATE cfg_rule_cutover_review SET confirmed_at=? WHERE id=?"
                    )
                ) {
                    update.setObject(1, now.atOffset(ZoneOffset.UTC));
                    update.setObject(2, id);
                    update.executeUpdate();
                }
                return new Review(
                    id,
                    current.accountId(),
                    actor,
                    current.grantEpoch(),
                    current.intent(),
                    reviewDigest,
                    current.expiresAt(),
                    now
                );
            }
        );
    }

    @Transactional
    public Review confirmed(UUID actor, String authorization, UUID id) {
        var observed = jdbc.execute(
            (ConnectionCallback<Review>) connection -> find(connection, actor, id)
        );
        var grant = grants.grant(actor, authorization, observed.intent().grantId());
        require(
            grant.accountId().equals(observed.accountId()) && "ACTIVE".equals(grant.status()),
            "AUTOMATION_GRANT_INACTIVE"
        );
        return jdbc.execute(
            (ConnectionCallback<Review>) connection -> {
                lockAccount(connection, observed.accountId());
                var current = find(connection, actor, id);
                lockGrant(
                    connection,
                    current.accountId(),
                    actor,
                    current.intent(),
                    current.grantEpoch(),
                    OwnerDatabaseClock.now(connection)
                );
                require(
                    OwnerDatabaseClock.now(connection).isBefore(current.expiresAt()),
                    "REVIEW_EXPIRED"
                );
                require(current.confirmedAt() != null, "REVIEW_STATE_CONFLICT");
                return current;
            }
        );
    }

    private static void lockAccount(Connection connection, UUID account) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT account_id FROM cfg_rule_owner_fence WHERE account_id=? FOR UPDATE"
            )
        ) {
            query.setObject(1, account);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "OWNER_ACCESS_REVOKED");
            }
        }
    }

    private static Instant lockGrant(
        Connection connection,
        UUID account,
        UUID actor,
        VclRuleCutoverIntent intent,
        long epoch,
        Instant now
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                """
                SELECT expires_at FROM cfg_automation_grant
                WHERE id=? AND account_id=? AND actor_id=? AND rule_id=? AND epoch=? AND status='ACTIVE' FOR UPDATE
                """
            )
        ) {
            query.setObject(1, intent.grantId());
            query.setObject(2, account);
            query.setObject(3, actor);
            query.setObject(4, intent.ruleId());
            query.setLong(5, epoch);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "AUTOMATION_GRANT_INACTIVE");
                Instant expiry = rows.getObject(1, OffsetDateTime.class).toInstant();
                require(
                    now.isBefore(expiry) && OwnerDatabaseClock.now(connection).isBefore(expiry),
                    "AUTOMATION_GRANT_INACTIVE"
                );
                return expiry;
            }
        }
    }

    private static Review find(Connection connection, UUID actor, UUID id) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT * FROM cfg_rule_cutover_review WHERE id=? AND actor_id=?"
            )
        ) {
            query.setObject(1, id);
            query.setObject(2, actor);
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
        long epoch = rows.getLong("grant_epoch");
        var intent = new VclRuleCutoverIntent(
            rows.getObject("rule_id", UUID.class),
            rows.getObject("grant_id", UUID.class),
            rows.getObject("predecessor_epoch", UUID.class),
            rows.getObject("successor_epoch", UUID.class),
            rows.getObject("bundle_id", UUID.class),
            rows.getString("bundle_digest"),
            rows.getString("checkpoint_digest")
        );
        String digest = rows.getString("review_digest");
        require(intent.reviewDigest(account, actor, id, epoch).equals(digest), "REVIEW_CONFLICT");
        var confirmed = rows.getObject("confirmed_at", OffsetDateTime.class);
        return new Review(
            id,
            account,
            actor,
            epoch,
            intent,
            digest,
            rows.getObject("expires_at", OffsetDateTime.class).toInstant(),
            confirmed == null ? null : confirmed.toInstant()
        );
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new VclException(code, "");
    }
}
