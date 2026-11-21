package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;

/** Completeness comes from contiguous committed cuts and their exact checkpoint sets. */
final class NativeHistoryEvidence {
    static final int MAX_CUTS = 1000;
    static final int MAX_CHECKPOINTS = 100_000;
    record Coverage(long first, long through, Long last) { }
    private NativeHistoryEvidence() { }

    static Coverage verify(Connection connection, NativeDataset dataset, long through) throws SQLException {
        var range = range(connection, dataset);
        long first = range.first();
        Long last = range.last();
        require(first <= through && through <= (last == null ? dataset.dataVersion() : last), "HISTORY_CUT_UNAVAILABLE");
        require(through - first < MAX_CUTS, "HISTORY_BUDGET_EXCEEDED");
        verifyPrefix(connection, dataset, first, through);
        return new Coverage(first, through, last);
    }

    static Coverage range(Connection connection, NativeDataset dataset) throws SQLException {
        long first;
        Long last;
        try (var query = connection.prepareStatement("SELECT account_id,schema_revision,physical_id,first_data_version,last_data_version FROM cfg_native_history_generation WHERE dataset_id=? AND generation=?")) {
            query.setObject(1, dataset.schema().id());
            query.setObject(2, dataset.generation());
            try (var row = query.executeQuery()) {
                require(row.next(), "HISTORY_BASELINE_REQUIRED");
                require(dataset.accountId().equals(row.getObject(1, java.util.UUID.class)) && dataset.schema().revision() == row.getLong(2)
                        && dataset.physicalId().equals(row.getObject(3, java.util.UUID.class)), "HISTORY_INTEGRITY");
                first = row.getLong(4);
                last = row.getObject(5) == null ? null : row.getLong(5);
            }
        }
        return new Coverage(first, last == null ? dataset.dataVersion() : last, last);
    }

    static void verifyPoint(Connection connection, NativeDataset dataset, long cut, boolean deleted, String kind) throws SQLException {
        var range = range(connection, dataset);
        if (kind.equals("RETIREMENT")) {
            require(deleted && range.last() != null && cut == range.last() + 1, "HISTORY_INTEGRITY");
            return;
        }
        require(range.first() <= cut && cut <= range.through()
                && (kind.equals("BASELINE") && cut == range.first() || kind.equals("MUTATION") && cut > range.first()), "HISTORY_INTEGRITY");
        verifyPrefix(connection, dataset, cut, cut);
    }

    private static void verifyPrefix(Connection connection, NativeDataset dataset, long first, long through) throws SQLException {
        var records = new java.util.TreeMap<Long, com.fasterxml.jackson.databind.node.ArrayNode>();
        int count = 0;
        try (var query = connection.prepareStatement("""
                SELECT data_version,record_id,record_version,deleted,value_digest FROM cfg_native_history_checkpoint
                WHERE dataset_id=? AND generation=? AND data_version>=? AND data_version<=? AND capture_kind<>'RETIREMENT'
                ORDER BY data_version,record_id,record_version LIMIT 100001
                """)) {
            query.setObject(1, dataset.schema().id());
            query.setObject(2, dataset.generation());
            query.setLong(3, first);
            query.setLong(4, through);
            query.setFetchSize(256);
            query.setQueryTimeout(2);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    require(++count <= MAX_CHECKPOINTS, "HISTORY_BUDGET_EXCEEDED");
                    var entries = records.computeIfAbsent(rows.getLong(1), ignored -> JsonNodeFactory.instance.arrayNode());
                    entries.addObject().put("recordId", rows.getObject(2, java.util.UUID.class).toString())
                            .put("recordVersion", Long.toString(rows.getLong(3))).put("deleted", rows.getBoolean(4)).put("valueDigest", rows.getString(5));
                }
            }
        }
        long expected = first;
        try (var query = connection.prepareStatement("SELECT data_version,row_count,record_set_digest FROM cfg_native_history_commit WHERE dataset_id=? AND generation=? AND data_version>=? AND data_version<=? ORDER BY data_version LIMIT 1001")) {
            query.setObject(1, dataset.schema().id());
            query.setObject(2, dataset.generation());
            query.setLong(3, first);
            query.setLong(4, through);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    require(rows.getLong(1) == expected++, "HISTORY_INTEGRITY");
                    var entries = records.remove(rows.getLong(1));
                    if (entries == null) entries = JsonNodeFactory.instance.arrayNode();
                    require(entries.size() == rows.getInt(2) && VclCanonical.sha256(VclCanonical.encode(entries)).equals(rows.getString(3)), "HISTORY_INTEGRITY");
                }
            }
        }
        require(expected == through + 1 && records.isEmpty(), "HISTORY_INTEGRITY");
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
