package com.voltcanary.alert.configurable.adapter;

import com.voltcanary.alert.configurable.domain.AutomationGrant;
import com.voltcanary.alert.configurable.domain.GrantReview;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Owner-local transaction/locking helpers; callers still obtain authority from actual owners. */
@Component
@RequiredArgsConstructor
public class GrantSql {
    private final DataSource source;

    <T> T transaction(UUID account, Work<T> work) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                    execute(connection, "SET LOCAL lock_timeout='2s'");
                    execute(connection, "SET LOCAL statement_timeout='10s'");
                }
                try (var query = connection.prepareStatement("SELECT account_id FROM cfg_rule_owner_fence WHERE account_id=? FOR UPDATE")) {
                    query.setObject(1, account);
                    try (var rows = query.executeQuery()) { if (!rows.next()) throw new VclException("REVIEW_UNAVAILABLE", ""); }
                }
                execute(connection, "UPDATE cfg_grant_review SET status='EXPIRED' WHERE account_id=? AND status IN ('RESERVED','PREPARED') AND expires_at<=?",
                        account, OffsetDateTime.ofInstant(now(connection), java.time.ZoneOffset.UTC));
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) { throw new VclException("GRANT_STORAGE_UNAVAILABLE", ""); }
    }

    GrantReview review(Connection connection, UUID account, UUID actor, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_grant_review WHERE id=? AND account_id=? AND actor_id=? FOR UPDATE")) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new VclException("REVIEW_UNAVAILABLE", "");
                return review(rows);
            }
        }
    }

    static GrantReview review(ResultSet row) throws SQLException {
        return new GrantReview(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class), row.getObject("actor_id", UUID.class),
                row.getObject("rule_review_id", UUID.class), row.getObject("rule_id", UUID.class), row.getObject("grant_id", UUID.class),
                row.getObject("receipt_id", UUID.class), row.getString("reservation_key"), row.getLong("revision"), row.getString("status"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(), row.getString("canonical_definition"), row.getString("canonical_pins"),
                row.getString("review_digest"), row.getString("outcome_code"));
    }

    AutomationGrant grant(Connection connection, UUID actor, UUID id) throws SQLException {
        AutomationGrant value;
        try (var query = connection.prepareStatement("SELECT * FROM cfg_automation_grant WHERE id=? AND actor_id=? FOR UPDATE")) {
            query.setObject(1, id);
            query.setObject(2, actor);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new VclException("GRANT_UNAVAILABLE", "");
                value = new AutomationGrant(id, row.getObject("account_id", UUID.class), actor, row.getObject("rule_id", UUID.class),
                        row.getObject("review_id", UUID.class), row.getString("canonical_definition"), row.getString("canonical_pins"),
                        row.getString("definition_digest"), row.getLong("epoch"), row.getString("status"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant());
            }
        }
        return value.effectiveAt(now(connection));
    }

    UUID grantAccount(UUID actor, UUID id) {
        try (var connection = source.getConnection(); var query = connection.prepareStatement("SELECT account_id FROM cfg_automation_grant WHERE id=? AND actor_id=?")) {
            query.setObject(1, id);
            query.setObject(2, actor);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new VclException("GRANT_UNAVAILABLE", "");
                return rows.getObject(1, UUID.class);
            }
        } catch (SQLException failure) { throw new VclException("GRANT_STORAGE_UNAVAILABLE", ""); }
    }

    UUID revocationAccount(UUID actor, UUID id) {
        try (var connection = source.getConnection(); var query = connection.prepareStatement("SELECT account_id FROM cfg_grant_revocation WHERE id=? AND actor_id=?")) {
            query.setObject(1, id);
            query.setObject(2, actor);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new VclException("REVIEW_UNAVAILABLE", "");
                return rows.getObject(1, UUID.class);
            }
        } catch (SQLException failure) { throw new VclException("GRANT_STORAGE_UNAVAILABLE", ""); }
    }

    static Instant now(Connection connection) throws SQLException {
        return OwnerDatabaseClock.now(connection);
    }

    static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) query.setObject(index + 1, arguments[index]);
            query.executeUpdate();
        }
    }

    @FunctionalInterface interface Work<T> { T run(Connection connection) throws SQLException; }
}
