package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeImportInput;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** Seals exact row IDs, versions and typed mutations before confirmation. */
final class NativeImportPlans {
    static final int MAX_PLAN_BYTES = 16 * 1_048_576;
    private static final com.fasterxml.jackson.databind.ObjectMapper PLAN_JSON = new com.fasterxml.jackson.databind.ObjectMapper(
            com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(64)
                            .maxStringLength(1_048_576).maxNumberLength(64).build()).build());
    record Validation(ArrayNode rows, ArrayNode errors, int total, int valid, boolean complete) {
        boolean approvable() { return complete && total == valid; }
    }
    private static final String INCOMPLETE_PROFILE = "native-import-incomplete-1";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private NativeImportPlans() { }

    static JsonNode decode(String canonical) {
        return payload(canonical, true);
    }

    static JsonNode payload(String canonical, boolean array) {
        require(canonical != null && canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_PLAN_BYTES, "IMPORT_INTEGRITY");
        try (var parser = PLAN_JSON.createParser(canonical)) {
            JsonNode plan = PLAN_JSON.readTree(parser);
            require(plan != null && (array ? plan.isArray() && plan.size() <= 10_001 : plan.isObject()) && parser.nextToken() == null, "IMPORT_INTEGRITY");
            return plan;
        } catch (java.io.IOException invalid) { throw new VclException("IMPORT_INTEGRITY", ""); }
    }

    static Validation validate(Connection connection, NativeDataset dataset, String scope, UUID job, VclDocument document, byte[] bytes,
            java.util.Map<UUID, NativeDataset> owners) throws SQLException {
        var spec = document.spec();
        var parsed = NativeImportInput.readPrefix(bytes, spec);
        var input = parsed.rows();
        var references = NativeImportReferences.prepare(connection, owners, input, spec.path("columns"));
        var rows = JSON.arrayNode();
        var errors = JSON.arrayNode();
        var sourceFields = new HashSet<String>();
        spec.path("columns").forEach(column -> sourceFields.add(column.path("sourceColumn").asText()));
        var unique = new HashSet<String>();
        int ordinal = 0, valid = 0;
        for (var row : input) {
            ordinal++;
            try {
                var actual = new HashSet<String>();
                row.fieldNames().forEachRemaining(actual::add);
                require(actual.equals(sourceFields), "FORMAT_INVALID");
                var values = new LinkedHashMap<UUID, JsonNode>();
                for (var column : spec.path("columns")) values.put(UUID.fromString(column.path("targetFieldId").asText()), NativeImportInput.convert(row, column, references));
                String tuple = NativeRowCodec.keyTuple(dataset.schema(), values);
                require(unique.add(tuple), "DUPLICATE_KEY");
                UUID record = UUID.randomUUID();
                String version = null;
                String createdAt = null;
                try (var query = connection.prepareStatement("SELECT * FROM " + NativeSqlLayout.identifier(new NativeSqlLayout(dataset).table()) + " WHERE external_key=?")) {
                    query.setString(1, VclCanonical.sha256(tuple));
                    try (var found = query.executeQuery()) {
                        if (found.next()) {
                            var previous = NativeRowCodec.read(found, dataset, dataset.schema().keys());
                            require(tuple.equals(NativeRowCodec.keyTuple(dataset.schema(), previous.values())), "KEY_IDENTITY_COLLISION");
                            require(!spec.path("mode").asText().equals("ATOMIC_INSERT"), "DUPLICATE_KEY");
                            record = previous.recordId();
                            version = previous.recordVersion();
                            createdAt = found.getObject("created_at", java.time.OffsetDateTime.class).toInstant().toString();
                        }
                    }
                }
                var root = JSON.objectNode().put("language", "VCL/1").put("kind", "MUTATION");
                var mutation = root.putObject("spec").put("mutationId", UUID.randomUUID().toString()).put("reviewId", job.toString())
                        .put("operation", version == null ? "INSERT" : "UPDATE").put("idempotencyKey", job + ":" + ordinal);
                if (version == null) mutation.putNull("expectedRowVersion"); else mutation.put("expectedRowVersion", version);
                mutation.set("target", spec.path("target"));
                mutation.set("targetPrecondition", spec.path("targetPrecondition"));
                var keys = mutation.putArray("key");
                var fields = mutation.putArray("values");
                for (UUID key : dataset.schema().keys()) keys.addObject().put("fieldId", key.toString()).set("literal", values.get(key));
                values.forEach((id, value) -> {
                    if (!dataset.schema().keys().contains(id)) fields.addObject().put("fieldId", id.toString()).set("literal", value);
                });
                // The public UPDATE grammar requires a value. Key-only imports still have
                // a real matched row/version; validate its typed keys as INSERT and seal
                // the owner-resolved version separately for the internal batch transition.
                boolean keyOnlyMatch = version != null && fields.isEmpty();
                if (keyOnlyMatch) mutation.put("operation", "INSERT").putNull("expectedRowVersion");
                String canonical = NativeMutationValidation.compile(connection, dataset, scope, NativeMutationValidation.PARSER.parse(root.toString()), record, references);
                var planned = JSON.objectNode().put("recordId", record.toString()).put("document", canonical);
                if (keyOnlyMatch) planned.put("matchedRowVersion", version);
                if (createdAt != null) planned.put("createdAt", createdAt);
                if (spec.path("mode").asText().equals("STAGED_REPLACE")) NativeStageRows.version(planned);
                rows.add(planned);
                valid++;
            } catch (VclException failure) {
                if (errors.size() < 100) errors.addObject().put("recordOrdinal", ordinal).put("code", failure.code()).put("stage", "VALIDATION");
            }
        }
        if (!parsed.complete()) {
            var incomplete = JSON.arrayNode();
            incomplete.addObject().put("profile", INCOMPLETE_PROFILE).put("reason", "ROW_LIMIT_REACHED");
            return new Validation(incomplete, errors, input.size(), valid, false);
        }
        if (spec.path("mode").asText().equals("STAGED_REPLACE") && input.size() == valid) {
            rows.insert(0, NativeStageRows.manifest(connection, dataset, rows));
        }
        var pins = references.manifest();
        if (!pins.isEmpty()) {
            if (rows.isEmpty() || rows.get(0).has("document")) rows.insertObject(0).put("profile", "native-import-references-1");
            ((com.fasterxml.jackson.databind.node.ObjectNode) rows.get(0)).set("references", pins);
        }
        return new Validation(rows, errors, input.size(), valid, true);
    }

    static boolean incomplete(JsonNode plan) {
        return INCOMPLETE_PROFILE.equals(plan.path(0).path("profile").asText());
    }

    static void recheck(Connection connection, NativeDataset dataset, String scope, JsonNode rows) throws SQLException {
        var references = NativeImportReferences.recheck(connection, dataset, rows);
        for (var row : rows) {
            if (!row.has("document")) continue;
            var document = NativeMutationValidation.PARSER.parse(row.path("document").asText());
            String canonical = NativeMutationValidation.compile(connection, dataset, scope, document, UUID.fromString(row.path("recordId").asText()), references);
            require(canonical.equals(row.path("document").asText()), "IMPORT_INTEGRITY");
        }
    }

    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
}
