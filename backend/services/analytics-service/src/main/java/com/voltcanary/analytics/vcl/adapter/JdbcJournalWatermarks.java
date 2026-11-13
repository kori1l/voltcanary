package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Stores diagnostic progress for a contiguous covered interval within one deployment scope. */
final class JdbcJournalWatermarks {
    private JdbcJournalWatermarks() { }

    record Candidate(String scopeDigest, Instant from, Instant until, Instant watermark, String evidenceDigest,
            com.voltcanary.analytics.vcl.VclWindowPlan.Window finalizing) { }

    static void requireFinalizationFrontier(Connection connection, KeyedStateStore.Key key, Candidate candidate)
            throws SQLException {
        if (candidate.finalizing() == null) return;
        try (var query = JdbcKeyedStateStore.prepare(connection, """
                SELECT covered_from,covered_until,watermark FROM vcl_journal_watermarks
                WHERE account_id=? AND deployment_epoch=? AND scope_digest=?
                """)) {
            bind(query, key, candidate);
            var window = candidate.finalizing();
            try (var rows = query.executeQuery()) {
                if (rows.next()
                        && !rows.getObject(1, OffsetDateTime.class).toInstant().isAfter(window.startInclusive())
                        && !rows.getObject(2, OffsetDateTime.class).toInstant().isBefore(window.endExclusive())
                        && !rows.getObject(3, OffsetDateTime.class).toInstant().isBefore(window.endExclusive())) return;
            }
        }
        throw new com.voltcanary.vcl.VclException("WINDOW_FRONTIER_PENDING", "");
    }

    /** The caller holds the deployment row lock. State, output and progress commit together. */
    static void advance(Connection connection, KeyedStateStore.Key key, Candidate candidate) throws SQLException {
        try (var query = JdbcKeyedStateStore.prepare(connection, """
                SELECT covered_until,watermark FROM vcl_journal_watermarks
                WHERE account_id=? AND deployment_epoch=? AND scope_digest=?
                """)) {
            bind(query, key, candidate);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    Instant until = rows.getObject(1, OffsetDateTime.class).toInstant();
                    Instant watermark = rows.getObject(2, OffsetDateTime.class).toInstant();
                    if (candidate.from().isAfter(until) || !candidate.until().isAfter(until)
                            || !candidate.watermark().isAfter(watermark)) return;
                    update(connection, key, candidate);
                    return;
                }
            }
        }
        try (var insert = JdbcKeyedStateStore.prepare(connection, """
                INSERT INTO vcl_journal_watermarks(account_id,deployment_epoch,scope_digest,
                covered_from,covered_until,watermark,evidence_digest) VALUES (?,?,?,?,?,?,?)
                """)) {
            bind(insert, key, candidate);
            insert.setObject(4, candidate.from().atOffset(ZoneOffset.UTC));
            insert.setObject(5, candidate.until().atOffset(ZoneOffset.UTC));
            insert.setObject(6, candidate.watermark().atOffset(ZoneOffset.UTC));
            insert.setString(7, candidate.evidenceDigest());
            insert.executeUpdate();
        }
    }

    private static void update(Connection connection, KeyedStateStore.Key key, Candidate candidate) throws SQLException {
        try (var update = JdbcKeyedStateStore.prepare(connection, """
                UPDATE vcl_journal_watermarks SET covered_until=?,watermark=?,evidence_digest=?
                WHERE account_id=? AND deployment_epoch=? AND scope_digest=?
                """)) {
            update.setObject(1, candidate.until().atOffset(ZoneOffset.UTC));
            update.setObject(2, candidate.watermark().atOffset(ZoneOffset.UTC));
            update.setString(3, candidate.evidenceDigest());
            update.setObject(4, key.accountId());
            update.setObject(5, key.deploymentEpoch());
            update.setString(6, candidate.scopeDigest());
            update.executeUpdate();
        }
    }

    private static void bind(java.sql.PreparedStatement statement, KeyedStateStore.Key key, Candidate candidate)
            throws SQLException {
        statement.setObject(1, key.accountId());
        statement.setObject(2, key.deploymentEpoch());
        statement.setString(3, candidate.scopeDigest());
    }
}
