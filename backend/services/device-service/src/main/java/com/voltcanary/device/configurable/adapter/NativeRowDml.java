package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** One row's checked write, dense optional history, and generation/head CAS in the caller's transaction. */
final class NativeRowDml {
    record Result(UUID recordId, long recordVersion, long dataVersion, boolean deleted) { }
    private NativeRowDml() { }

    static Result mutate(Connection connection, NativeDataset dataset, JsonNode spec, UUID reservedId) throws SQLException {
        return mutate(connection, dataset, spec, reservedId, true);
    }

    static Result mutate(Connection connection, NativeDataset dataset, JsonNode spec, UUID reservedId, boolean advanceHead) throws SQLException {
        NativeHistoryCapture.ensure(connection, dataset);
        var values = new LinkedHashMap<UUID, JsonNode>();
        for (var pair : spec.path("key")) values.put(UUID.fromString(pair.path("fieldId").asText()), pair.path("literal"));
        for (var pair : spec.path("values")) values.put(UUID.fromString(pair.path("fieldId").asText()), pair.path("literal"));
        var layout = new NativeSqlLayout(dataset);
        String table = NativeSqlLayout.identifier(layout.table());
        String operation = spec.path("operation").asText();
        String tuple = dataset.schema().keys().isEmpty() ? null : NativeRowCodec.keyTuple(dataset.schema(), values);
        String hash = tuple == null ? null : VclCanonical.sha256(tuple);
        boolean insert = operation.equals("INSERT");
        // Let the existing unique index handle the successful PostgreSQL insert in one round trip.
        // The conflict path still reads and compares the full typed tuple.
        boolean indexArbitrates = insert && hash != null && connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        NativeRow previous = indexArbitrates ? null : find(connection, dataset, spec.path("key"), hash);
        UUID recordId;
        long version;
        if (insert) {
            if (previous != null) rejectInsert(dataset, previous, tuple);
            recordId = reservedId;
            version = 1;
            var columns = new ArrayList<String>(List.of("record_id", "record_version", "external_key"));
            var parameters = new ArrayList<Object>();
            parameters.add(recordId);
            parameters.add(version);
            parameters.add(hash);
            for (var field : dataset.schema().fields()) {
                UUID id = UUID.fromString(field.path("id").asText());
                columns.addAll(NativeRowCodec.columns(dataset, id));
                var value = values.getOrDefault(id, JsonNodeFactory.instance.objectNode().put("type", "NULL").putNull("value"));
                parameters.addAll(NativeRowCodec.encode(dataset.schema(), id, value));
            }
            String sql = "INSERT INTO " + table + " (" + columns.stream().map(NativeSqlLayout::identifier).collect(java.util.stream.Collectors.joining(","))
                    + ") VALUES(" + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")";
            if (indexArbitrates) {
                if (update(connection, sql + " ON CONFLICT (external_key) DO NOTHING", parameters) == 0) {
                    rejectInsert(dataset, find(connection, dataset, spec.path("key"), hash), tuple);
                }
            } else {
                execute(connection, sql, parameters);
            }
        } else {
            if (previous == null) throw new VclException("ROW_UNAVAILABLE", "");
            if (tuple != null && !NativeRowCodec.keyTuple(dataset.schema(), previous.values()).equals(tuple)) throw new VclException("KEY_IDENTITY_COLLISION", "");
            long expected = Long.parseLong(spec.path("expectedRowVersion").asText());
            if (!previous.recordVersion().equals(Long.toString(expected))) throw new VclException("ROW_CONFLICT", "");
            if (expected == Long.MAX_VALUE) throw new VclException("ROW_VERSION_LIMIT", "");
            recordId = previous.recordId();
            version = Math.addExact(expected, 1);
            if (operation.equals("DELETE")) {
                NativeReferenceGuards.deleting(connection, dataset, recordId);
                execute(connection, "DELETE FROM " + table + " WHERE record_id=? AND record_version=?", List.of(recordId, expected));
            } else {
                var assignments = new ArrayList<String>();
                var parameters = new ArrayList<Object>();
                for (var pair : spec.path("values")) {
                    UUID id = UUID.fromString(pair.path("fieldId").asText());
                    for (String column : NativeRowCodec.columns(dataset, id)) assignments.add(NativeSqlLayout.identifier(column) + "=?");
                    parameters.addAll(NativeRowCodec.encode(dataset.schema(), id, pair.path("literal")));
                }
                assignments.add("record_version=?");
                assignments.add("updated_at=CURRENT_TIMESTAMP");
                parameters.add(version);
                parameters.add(recordId);
                parameters.add(expected);
                execute(connection, "UPDATE " + table + " SET " + String.join(",", assignments) + " WHERE record_id=? AND record_version=?", parameters);
            }
        }
        if (dataset.schema().history()) {
            history(connection, dataset, recordId, version, operation.equals("DELETE"), previous);
            NativeHistoryCapture.mutation(connection, dataset, recordId, version, operation.equals("DELETE"));
        }
        if (advanceHead) advanceHead(connection, dataset);
        return new Result(recordId, version, dataset.dataVersion() + 1, operation.equals("DELETE"));
    }

    private static void rejectInsert(NativeDataset dataset, NativeRow existing, String tuple) {
        if (existing == null) throw new VclException("ROW_CONFLICT", "");
        if (!NativeRowCodec.keyTuple(dataset.schema(), existing.values()).equals(tuple)) throw new VclException("KEY_IDENTITY_COLLISION", "");
        throw new VclException("ROW_IDENTITY_CONFLICT", "");
    }

    static void advanceHead(Connection connection, NativeDataset dataset) throws SQLException {
        NativeHistoryCapture.ensure(connection, dataset);
        NativeHistoryCapture.seal(connection, dataset, dataset.dataVersion() + 1);
        if (dataset.dataVersion() >= 9_007_199_254_740_991L) throw new VclException("DATA_VERSION_LIMIT", "");
        try (var head = connection.prepareStatement("UPDATE cfg_dataset SET data_version=data_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND account_id=? AND generation=? AND data_version=? AND status='READY'")) {
            head.setObject(1, dataset.schema().id());
            head.setObject(2, dataset.accountId());
            head.setObject(3, dataset.generation());
            head.setLong(4, dataset.dataVersion());
            if (head.executeUpdate() != 1) throw new VclException("TARGET_CONFLICT", "");
        }
    }

    private static NativeRow find(Connection connection, NativeDataset dataset, JsonNode keys, String hash) throws SQLException {
        String condition;
        Object key;
        if (dataset.schema().keys().isEmpty()) {
            if (keys.isEmpty()) return null;
            condition = "record_id=?";
            key = UUID.fromString(keys.get(0).at("/literal/value").asText());
        } else {
            condition = "external_key=?";
            key = hash;
        }
        try (var query = connection.prepareStatement("SELECT * FROM " + NativeSqlLayout.identifier(new NativeSqlLayout(dataset).table()) + " WHERE " + condition + " FOR UPDATE")) {
            query.setObject(1, key);
            try (var rows = query.executeQuery()) {
                return rows.next() ? NativeRowCodec.read(rows, dataset, dataset.schema().fields().stream().map(field -> UUID.fromString(field.path("id").asText())).toList()) : null;
            }
        }
    }

    private static void history(Connection connection, NativeDataset dataset, UUID id, long version, boolean deleted, NativeRow previous) throws SQLException {
        var layout = new NativeSqlLayout(dataset);
        if (!deleted) {
            String columns = layout.columns().stream().map(column -> NativeSqlLayout.identifier(column.name())).collect(java.util.stream.Collectors.joining(","));
            execute(connection, "INSERT INTO " + NativeSqlLayout.identifier(layout.historyTable()) + " (record_id,record_version,deleted," + columns
                    + ") SELECT record_id,record_version,FALSE," + columns + " FROM " + NativeSqlLayout.identifier(layout.table()) + " WHERE record_id=?", List.of(id));
        } else {
            var columns = new ArrayList<String>(List.of("record_id", "record_version", "deleted"));
            var values = new ArrayList<Object>(List.of(id, version, true));
            var oldValues = previous.values();
            for (var field : dataset.schema().fields()) {
                UUID fieldId = UUID.fromString(field.path("id").asText());
                columns.addAll(NativeRowCodec.columns(dataset, fieldId));
                values.addAll(NativeRowCodec.encode(dataset.schema(), fieldId, oldValues.get(fieldId)));
            }
            execute(connection, "INSERT INTO " + NativeSqlLayout.identifier(layout.historyTable()) + " (" + columns.stream().map(NativeSqlLayout::identifier).collect(java.util.stream.Collectors.joining(","))
                    + ") VALUES(" + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")", values);
        }
    }

    private static void execute(Connection connection, String sql, List<Object> parameters) throws SQLException {
        if (update(connection, sql, parameters) != 1) throw new VclException("ROW_CONFLICT", "");
    }

    private static int update(Connection connection, String sql, List<Object> parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
            return statement.executeUpdate();
        }
    }
}
