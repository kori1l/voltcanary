package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.device.configurable.domain.NativeSchemaMapping;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Complete bounded source scan, conversion and sealed target rows before any shadow DDL. */
final class NativeSchemaMigrationPlans {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private record Source(NativeRow row, String createdAt) { }
    record Validation(ArrayNode plan, ArrayNode errors, int totalRows, int validRows) { }

    private NativeSchemaMigrationPlans() { }

    static Validation prepare(Connection connection, NativeDataset previous, NativeDataset next, String scope,
            UUID review, NativeSchemaMapping mapping, boolean reset) throws SQLException {
        var source = read(connection, previous);
        var columns = mapping.columns();
        var conversionSpec = JSON.objectNode();
        conversionSpec.set("columns", columns);
        var owners = NativeImportReferences.owners(connection, previous, conversionSpec);
        var raw = new ArrayList<JsonNode>();
        if (!reset) for (var item : source) {
            var row = JSON.objectNode();
            item.row().values().forEach((id, literal) -> row.set(id.toString(), literal.path("value")));
            raw.add(row);
        }
        var references = NativeImportReferences.prepare(connection, owners, raw, columns);
        var plan = JSON.arrayNode();
        var header = plan.addObject().put("profile", "native-schema-migration-1")
                .put("sourceSchemaDigest", previous.schema().digest()).put("sourceGeneration", previous.generation().toString())
                .put("sourceDataVersion", previous.dataVersion()).put("targetSchemaDigest", next.schema().digest())
                .put("sourceRows", source.size()).put("reset", reset);
        header.set("references", references.manifest());
        var errors = JSON.arrayNode();
        var keys = new HashSet<String>();
        int valid = 0;
        int bytes = VclCanonical.encode(header).getBytes(StandardCharsets.UTF_8).length + 2;
        for (int ordinal = 0; ordinal < source.size(); ordinal++) {
            if (reset) continue;
            var item = source.get(ordinal);
            try {
                long version = Long.parseLong(item.row().recordVersion());
                require(version < Long.MAX_VALUE, "ROW_VERSION_LIMIT");
                var values = mapping.convert(item.row().values(), references);
                if (!next.schema().keys().isEmpty()) require(keys.add(NativeRowCodec.keyTuple(next.schema(), values)), "DUPLICATE_KEY");
                var root = JSON.objectNode().put("language", "VCL/1").put("kind", "MUTATION");
                var spec = root.putObject("spec").put("mutationId", UUID.randomUUID().toString())
                        .put("reviewId", review.toString()).put("operation", "INSERT")
                        .put("idempotencyKey", review + ":migration:" + (ordinal + 1)).putNull("expectedRowVersion");
                spec.putObject("target").put("datasetId", next.schema().id().toString()).put("schemaRevision", next.schema().revision());
                spec.putObject("targetPrecondition").put("mode", "NATIVE")
                        .put("generation", next.generation().toString()).put("dataVersion", next.dataVersion());
                var key = spec.putArray("key");
                var fields = spec.putArray("values");
                for (var field : next.schema().fields()) {
                    UUID id = UUID.fromString(field.path("id").asText());
                    var pair = (next.schema().keys().contains(id) ? key : fields).addObject().put("fieldId", id.toString());
                    pair.set("literal", values.get(id));
                }
                // Validate references against the still-active source identities. The physical shadow
                // does not exist yet and cannot be used as an authority to invent rows.
                var validationTarget = new NativeDataset(previous.accountId(), next.generation(), next.dataVersion(),
                        previous.recordIdFieldId(), next.schema(), previous.physicalId());
                String canonical = NativeMutationValidation.compile(connection, validationTarget, scope,
                        NativeMutationValidation.PARSER.parse(root.toString()), item.row().recordId(), references);
                var row = JSON.objectNode().put("recordId", item.row().recordId().toString())
                        .put("sourceVersion", item.row().recordVersion()).put("matchedRowVersion", item.row().recordVersion())
                        .put("recordVersion", Long.toString(version + 1))
                        .put("createdAt", item.createdAt()).put("document", canonical);
                bytes += VclCanonical.encode(row).getBytes(StandardCharsets.UTF_8).length + 1;
                require(bytes <= NativeImportPlans.MAX_PLAN_BYTES, "MIGRATION_PLAN_LIMIT");
                plan.add(row);
                valid++;
            } catch (VclException invalid) {
                if (invalid.code().equals("MIGRATION_PLAN_LIMIT")) throw invalid;
                if (errors.size() < 100) errors.addObject().put("recordOrdinal", ordinal + 1).put("code", invalid.code());
            }
        }
        if (reset) {
            for (int start = 0; start < source.size(); start += 500) {
                var removed = new HashSet<UUID>();
                source.subList(start, Math.min(start + 500, source.size())).forEach(row -> removed.add(row.row().recordId()));
                NativeReferenceGuards.replacing(connection, previous, removed);
            }
            valid = source.size();
        }
        return new Validation(plan, errors, source.size(), valid);
    }

    private static List<Source> read(Connection connection, NativeDataset dataset) throws SQLException {
        var result = new ArrayList<Source>();
        var fields = dataset.schema().fields().stream().map(field -> UUID.fromString(field.path("id").asText())).toList();
        long bytes = 0;
        try (var query = connection.prepareStatement("SELECT * FROM "
                + NativeSqlLayout.identifier(new NativeSqlLayout(dataset).table()) + " ORDER BY record_id LIMIT 10001")) {
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    require(result.size() < 10_000, "MIGRATION_ROW_LIMIT");
                    var row = NativeRowCodec.read(rows, dataset, fields);
                    for (var value : row.values().values()) bytes += VclCanonical.encode(value).getBytes(StandardCharsets.UTF_8).length;
                    require(bytes <= NativeImportPlans.MAX_PLAN_BYTES, "MIGRATION_PLAN_LIMIT");
                    result.add(new Source(row, rows.getObject("created_at", java.time.OffsetDateTime.class).toInstant().toString()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
