package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exact replacement row set and deletion impact. Shadow names are reserved by the owner. */
final class NativeStageRows {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private NativeStageRows() {}

    static JsonNode manifest(
        Connection connection,
        NativeDataset dataset,
        ArrayNode plan
    ) throws SQLException {
        var retained = new HashSet<UUID>();
        plan.forEach(row -> retained.add(UUID.fromString(row.path("recordId").asText())));
        var manifest = JSON.objectNode()
            .put("profile", "native-generation-replace-1")
            .put("generation", UUID.randomUUID().toString())
            .put("physicalId", UUID.randomUUID().toString());
        var removed = manifest.putArray("removed");
        int count = 0;
        try (
            var query = connection.prepareStatement(
                "SELECT record_id,record_version FROM " +
                    NativeSqlLayout.identifier(new NativeSqlLayout(dataset).table()) +
                    " ORDER BY record_id LIMIT 501"
            );
            var rows = query.executeQuery()
        ) {
            while (rows.next()) {
                require(++count <= 500, "REPLACEMENT_TARGET_LIMIT");
                UUID id = rows.getObject("record_id", UUID.class);
                require(rows.getLong("record_version") < Long.MAX_VALUE, "ROW_VERSION_LIMIT");
                if (!retained.contains(id)) removed
                    .addObject()
                    .put("recordId", id.toString())
                    .put("recordVersion", Long.toString(rows.getLong("record_version")));
            }
        }
        // Complete explicit replacement semantics: omitted optional fields become NULL.
        // An empty artifact is allowed only through this reviewed deletion impact and exact digest.
        checkReferences(connection, dataset, plan, removed);
        return manifest;
    }

    static Map<UUID, JsonNode> values(NativeDataset dataset, JsonNode row) {
        var spec = NativeImportPlans.payload(row.path("document").asText(), false).path("spec");
        var values = new LinkedHashMap<UUID, JsonNode>();
        for (var field : dataset.schema().fields())
            values.put(
                UUID.fromString(field.path("id").asText()),
                JSON.objectNode().put("type", "NULL").putNull("value")
            );
        for (String group : List.of("key", "values"))
            for (var pair : spec.path(group))
                values.put(UUID.fromString(pair.path("fieldId").asText()), pair.path("literal"));
        return values;
    }

    static long version(JsonNode row) {
        var expected = originalVersion(row);
        try {
            return expected.isNull() ? 1 : Math.addExact(Long.parseLong(expected.asText()), 1);
        } catch (ArithmeticException overflow) {
            throw new VclException("ROW_VERSION_LIMIT", "");
        }
    }

    private static JsonNode originalVersion(JsonNode row) {
        return row.has("matchedRowVersion")
            ? row.path("matchedRowVersion")
            : NativeImportPlans.payload(row.path("document").asText(), false).at(
                  "/spec/expectedRowVersion"
              );
    }

    static void populate(
        Connection connection,
        NativeDataset stage,
        JsonNode plan
    ) throws SQLException {
        var layout = new NativeSqlLayout(stage);
        for (var row : plan) {
            if (!row.has("document")) continue;
            var values = values(stage, row);
            var columns = new ArrayList<String>(
                List.of("record_id", "record_version", "external_key")
            );
            var parameters = new ArrayList<Object>();
            parameters.add(UUID.fromString(row.path("recordId").asText()));
            parameters.add(version(row));
            parameters.add(
                stage.schema().keys().isEmpty()
                    ? null
                    : VclCanonical.sha256(NativeRowCodec.keyTuple(stage.schema(), values))
            );
            if (row.has("createdAt")) {
                columns.add("created_at");
                parameters.add(java.time.OffsetDateTime.parse(row.path("createdAt").asText()));
            }
            for (var field : stage.schema().fields()) {
                UUID id = UUID.fromString(field.path("id").asText());
                columns.addAll(NativeRowCodec.columns(stage, id));
                parameters.addAll(NativeRowCodec.encode(stage.schema(), id, values.get(id)));
            }
            execute(
                connection,
                "INSERT INTO " +
                    NativeSqlLayout.identifier(layout.table()) +
                    " (" +
                    columns
                        .stream()
                        .map(NativeSqlLayout::identifier)
                        .collect(java.util.stream.Collectors.joining(",")) +
                    ") VALUES(" +
                    String.join(",", java.util.Collections.nCopies(parameters.size(), "?")) +
                    ")",
                parameters
            );
        }
        if (stage.schema().history()) {
            String columns = layout
                .columns()
                .stream()
                .map(column -> NativeSqlLayout.identifier(column.name()))
                .collect(java.util.stream.Collectors.joining(","));
            JdbcNativeDatasetCatalog.execute(
                connection,
                "INSERT INTO " +
                    NativeSqlLayout.identifier(layout.historyTable()) +
                    " (record_id,record_version,deleted," +
                    columns +
                    ") SELECT record_id,record_version,FALSE," +
                    columns +
                    " FROM " +
                    NativeSqlLayout.identifier(layout.table())
            );
            NativeHistoryCapture.baseline(connection, stage);
        }
    }

    static void verify(
        Connection connection,
        NativeDataset stage,
        JsonNode plan
    ) throws SQLException {
        NativeCatalogReadiness.verify(connection, new NativeSqlLayout(stage));
        verifyTable(connection, stage, plan, false);
        if (stage.schema().history()) verifyTable(connection, stage, plan, true);
        if (stage.schema().history()) {
            var coverage = NativeHistoryEvidence.verify(connection, stage, stage.dataVersion());
            NativeHistoryState.verify(
                connection,
                stage,
                NativeHistoryState.sql(stage, null, coverage.first(), stage.dataVersion())
            );
        }
    }

    private static void verifyTable(
        Connection connection,
        NativeDataset stage,
        JsonNode plan,
        boolean history
    ) throws SQLException {
        var expected = new HashMap<UUID, JsonNode>();
        plan.forEach(row -> {
            if (row.has("document")) expected.put(
                UUID.fromString(row.path("recordId").asText()),
                row
            );
        });
        int count = 0;
        int limit =
            plan.size() > 0 &&
            plan.get(0).path("profile").asText().equals("native-schema-migration-1")
                ? 10_000
                : 500;
        var layout = new NativeSqlLayout(stage);
        try (
            var query = connection.prepareStatement(
                "SELECT * FROM " +
                    NativeSqlLayout.identifier(history ? layout.historyTable() : layout.table()) +
                    " LIMIT " +
                    (limit + 1)
            );
            var rows = query.executeQuery()
        ) {
            while (rows.next()) {
                require(++count <= limit, "STAGE_INTEGRITY");
                var actual = NativeRowCodec.read(
                    rows,
                    stage,
                    stage
                        .schema()
                        .fields()
                        .stream()
                        .map(field -> UUID.fromString(field.path("id").asText()))
                        .toList()
                );
                var row = expected.remove(actual.recordId());
                require(
                    row != null && actual.recordVersion().equals(Long.toString(version(row))),
                    "STAGE_INTEGRITY"
                );
                if (history) require(!rows.getBoolean("deleted"), "STAGE_INTEGRITY");
                if (!history && row.has("createdAt")) require(
                    rows
                        .getObject("created_at", java.time.OffsetDateTime.class)
                        .toInstant()
                        .equals(java.time.Instant.parse(row.path("createdAt").asText())),
                    "STAGE_INTEGRITY"
                );
                var wanted = values(stage, row);
                if (!history) {
                    String key = stage.schema().keys().isEmpty()
                        ? null
                        : VclCanonical.sha256(NativeRowCodec.keyTuple(stage.schema(), wanted));
                    require(
                        java.util.Objects.equals(key, rows.getString("external_key")),
                        "STAGE_INTEGRITY"
                    );
                }
                for (var entry : wanted.entrySet()) {
                    // SQL can preserve decimal scale; canonical typed values must remain equivalent.
                    JsonNode canonical = NativeMutationValidation.PARSER.literal(
                        entry.getValue().toString()
                    );
                    JsonNode found = NativeMutationValidation.PARSER.literal(
                        actual.values().get(entry.getKey()).toString()
                    );
                    require(
                        VclCanonical.encode(canonical).equals(VclCanonical.encode(found)),
                        "STAGE_INTEGRITY"
                    );
                }
            }
        }
        require(expected.isEmpty(), "STAGE_INTEGRITY");
    }

    static void checkReferences(
        Connection connection,
        NativeDataset old,
        JsonNode plan,
        JsonNode removed
    ) throws SQLException {
        var removedIds = new HashSet<UUID>();
        removed.forEach(row -> removedIds.add(UUID.fromString(row.path("recordId").asText())));
        NativeReferenceGuards.replacing(connection, old, removedIds);
        var selfReferences = old
            .schema()
            .fields()
            .stream()
            .filter(
                field ->
                    field.at("/target/kind").asText().equals("DATASET") &&
                    field.at("/target/datasetId").asText().equals(old.schema().id().toString())
            )
            .map(field -> UUID.fromString(field.path("id").asText()))
            .toList();
        if (selfReferences.isEmpty() || removedIds.isEmpty()) return;
        for (var row : plan) {
            if (!row.has("document")) continue;
            var values = values(old, row);
            for (UUID field : selfReferences) {
                var value = values.get(field);
                require(
                    !value.path("type").asText().equals("REF") ||
                        !removedIds.contains(UUID.fromString(value.path("value").asText())),
                    "ROW_REFERENCED"
                );
            }
        }
    }

    static void tombstones(
        Connection connection,
        NativeDataset old,
        JsonNode removed
    ) throws SQLException {
        if (!old.schema().history()) return;
        var layout = new NativeSqlLayout(old);
        String columns = layout
            .columns()
            .stream()
            .map(column -> NativeSqlLayout.identifier(column.name()))
            .collect(java.util.stream.Collectors.joining(","));
        for (var row : removed) {
            require(row.path("recordVersion").asLong() < Long.MAX_VALUE, "ROW_VERSION_LIMIT");
            execute(
                connection,
                "INSERT INTO " +
                    NativeSqlLayout.identifier(layout.historyTable()) +
                    " (record_id,record_version,deleted," +
                    columns +
                    ") SELECT record_id,record_version+1,TRUE," +
                    columns +
                    " FROM " +
                    NativeSqlLayout.identifier(layout.table()) +
                    " WHERE record_id=? AND record_version=?",
                List.of(
                    UUID.fromString(row.path("recordId").asText()),
                    row.path("recordVersion").asLong()
                )
            );
            NativeHistoryCapture.retirement(
                connection,
                old,
                UUID.fromString(row.path("recordId").asText()),
                Long.parseLong(row.path("recordVersion").asText()) + 1
            );
        }
    }

    static void checkOriginal(
        Connection connection,
        NativeDataset old,
        JsonNode plan
    ) throws SQLException {
        var expected = new HashMap<UUID, Long>();
        for (var row : plan) {
            if (!row.has("document")) {
                for (var removed : row.path("removed"))
                    expected.put(
                        UUID.fromString(removed.path("recordId").asText()),
                        removed.path("recordVersion").asLong()
                    );
            } else {
                var version = originalVersion(row);
                if (!version.isNull()) expected.put(
                    UUID.fromString(row.path("recordId").asText()),
                    Long.parseLong(version.asText())
                );
            }
        }
        int count = 0;
        try (
            var query = connection.prepareStatement(
                "SELECT record_id,record_version FROM " +
                    NativeSqlLayout.identifier(new NativeSqlLayout(old).table()) +
                    " LIMIT 501"
            );
            var rows = query.executeQuery()
        ) {
            while (rows.next()) {
                require(++count <= 500, "VALIDATION_STALE");
                Long version = expected.remove(rows.getObject("record_id", UUID.class));
                require(
                    version != null && version == rows.getLong("record_version"),
                    "VALIDATION_STALE"
                );
            }
        }
        require(expected.isEmpty(), "VALIDATION_STALE");
    }

    private static void execute(
        Connection connection,
        String sql,
        List<Object> parameters
    ) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) statement.setObject(
                i + 1,
                parameters.get(i)
            );
            require(statement.executeUpdate() == 1, "STAGE_INTEGRITY");
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
