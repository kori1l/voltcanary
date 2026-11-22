package com.voltcanary.analytics.vcl.adapter;

import static com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.VERSION;

import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Snapshot;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.Row;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore.Work;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;

/** Focused transactions operations sharing the original state-store transaction boundary. */
final class JdbcKeyedTransactions {

    private final DataSource source;

    JdbcKeyedTransactions(DataSource source) {
        this.source = source;
    }

    Row load(Connection connection, Key key, boolean lock) throws SQLException {
        try (
            var query = prepare(
                connection,
                "SELECT * FROM vcl_keyed_state WHERE account_id=? AND key_digest=?" +
                    (lock ? " FOR UPDATE" : "")
            )
        ) {
            bindKey(query, key);
            try (var row = query.executeQuery()) {
                require(row.next(), "KEY_UNAVAILABLE");
                require(
                    key.ruleId().equals(row.getObject("rule_id", UUID.class)) &&
                        key
                            .deploymentEpoch()
                            .equals(row.getObject("deployment_epoch", UUID.class)) &&
                        key.subjectDigest().equals(row.getString("subject_digest")) &&
                        key.occurrenceDigest().equals(row.getString("occurrence_digest")),
                    "KEY_IDENTITY_CHANGED"
                );
                require(VERSION.equals(row.getString("state_version")), "KEY_VERSION_UNAVAILABLE");
                var expiry = row.getObject("lease_expires_at", OffsetDateTime.class);
                return new Row(
                    row.getString("registration_digest"),
                    row.getString("pins_digest"),
                    new Snapshot(
                        row.getLong("state_revision"),
                        State.restore(row.getString("state_payload"), row.getString("state_digest"))
                    ),
                    row.getInt("inbox_count"),
                    row.getObject("lease_holder", UUID.class),
                    row.getLong("lease_fence"),
                    expiry == null ? null : expiry.toInstant()
                );
            }
        }
    }

    Connection connection() throws SQLException {
        var connection = source.getConnection();
        if (!connection.getAutoCommit()) {
            connection.close();
            throw new VclException("KEY_AUTOCOMMIT_REQUIRED", "");
        }
        return connection;
    }

    <T> T transaction(Work<T> work) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                String database = connection.getMetaData().getDatabaseProductName();
                if (database.equals("PostgreSQL")) {
                    try (var setting = prepare(connection, "SET LOCAL lock_timeout='2s'")) {
                        setting.execute();
                    }
                } else if (database.equals("H2")) {
                    try (var setting = prepare(connection, "SET LOCK_TIMEOUT 2000")) {
                        setting.execute();
                    }
                }
                var result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    /* Preserve the original failure. */
                }
                throw error;
            }
        } catch (SQLException error) {
            throw unavailable();
        }
    }

    static void bindKey(java.sql.PreparedStatement statement, Key key) throws SQLException {
        statement.setObject(1, key.accountId());
        statement.setString(2, key.digest());
    }

    Instant databaseTime() {
        return transaction(JdbcKeyedStateStore::now);
    }

    static Instant now(Connection connection) throws SQLException {
        String query = switch (connection.getMetaData().getDatabaseProductName()) {
            case "PostgreSQL" -> "SELECT clock_timestamp()";
            case "H2" -> "SELECT EXECUTING_STATEMENT_START FROM INFORMATION_SCHEMA.SESSIONS" +
                " WHERE SESSION_ID=SESSION_ID()";
            default -> throw new SQLException("Unsupported execution database clock");
        };
        try (
            var statement = prepare(connection, query);
            ResultSet rows = statement.executeQuery()
        ) {
            if (!rows.next() || rows.getObject(1) == null) throw new SQLException(
                "Execution database clock unavailable"
            );
            return rows.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    static java.sql.PreparedStatement prepare(
        Connection connection,
        String sql
    ) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(5);
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
        return statement;
    }

    static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }

    static VclException unavailable() {
        return new VclException("KEY_STORAGE_UNAVAILABLE", "");
    }
}
