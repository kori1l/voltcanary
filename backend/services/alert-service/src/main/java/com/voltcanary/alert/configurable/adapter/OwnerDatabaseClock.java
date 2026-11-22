package com.voltcanary.alert.configurable.adapter;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Fresh database time after row-lock waits, never the transaction-start timestamp. */
final class OwnerDatabaseClock {

    private OwnerDatabaseClock() {}

    static Instant now(Connection connection) throws SQLException {
        String query = switch (connection.getMetaData().getDatabaseProductName()) {
            case "PostgreSQL" -> "SELECT clock_timestamp()";
            // H2 PostgreSQL mode also fixes CURRENT_TIMESTAMP for the transaction.
            // Its own-session statement metadata supplies this clock query's fresh start time.
            case "H2" -> "SELECT EXECUTING_STATEMENT_START FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID=SESSION_ID()";
            default -> throw new SQLException("Unsupported owner database clock");
        };
        try (
            var statement = connection.prepareStatement(query);
            var rows = statement.executeQuery()
        ) {
            if (!rows.next() || rows.getObject(1) == null) throw new SQLException(
                "Owner database clock unavailable"
            );
            return rows.getObject(1, OffsetDateTime.class).toInstant();
        }
    }
}
