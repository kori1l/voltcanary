package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class VclImportChecks {
    private static final Map<String, VclType.Kind> PARSERS = Map.of("parse_int64", VclType.Kind.INT64,
            "parse_decimal", VclType.Kind.DECIMAL, "parse_boolean", VclType.Kind.BOOLEAN, "parse_date", VclType.Kind.DATE,
            "parse_instant", VclType.Kind.INSTANT, "parse_duration", VclType.Kind.DURATION);
    private final VclUnits units;
    private final VclCompiler.Session owner;

    VclImportChecks(VclUnits units, VclCompiler.Session owner) {
        this.units = units;
        this.owner = owner;
    }

    void check(JsonNode spec) {
        var dataset = owner.dataset(spec.path("target"), "/spec/target");
        var fields = owner.fields(dataset.path("fields"), "/spec/target");
        owner.precondition(dataset, spec.path("targetPrecondition"));
        VclCompiler.unique(spec.path("columns"), "sourceColumn", "/spec/columns");
        var mapped = VclCompiler.unique(spec.path("columns"), "targetFieldId", "/spec/columns");
        var keys = VclCompiler.strings(spec.path("keyFields"));
        VclException.require(!keys.isEmpty() && keys.equals(VclCompiler.strings(dataset.path("keyFieldIds"))),
                "UNIQUE_TARGET_KEY_REQUIRED", "/spec/keyFields");
        for (var key : keys) VclExpressionTypes.field(fields, key, "/spec/keyFields");
        if (Set.of("ATOMIC_INSERT", "ATOMIC_UPSERT").contains(spec.path("mode").asText())) {
            VclException.require(spec.at("/limits/maxRows").asLong() <= 500, "ATOMIC_ROW_LIMIT", "/spec/limits/maxRows");
        }
        for (var column : spec.path("columns")) {
            if (spec.at("/format/kind").asText().equals("CSV")) VclException.require(column.path("sourceType").asText().equals("TEXT"), "SOURCE_TYPE_MISMATCH", "/spec/columns");
            var target = VclExpressionTypes.field(fields, column.path("targetFieldId").asText(), "/spec/columns");
            column(column, new VclType(VclType.Kind.valueOf(column.path("sourceType").asText()), false,
                    column.path("sourceUnit").isNull() ? "1" : column.path("sourceUnit").asText(), null), target);
        }
        fields.forEach((id, type) -> VclException.require(type.nullable() || mapped.contains(id), "REQUIRED_FIELD_MISSING", "/spec/columns"));
        var hash = owner.metadata.artifactSha256(UUID.fromString(spec.path("artifactId").asText()));
        VclException.require(hash.isPresent() && hash.get().equals(spec.path("artifactSha256").asText()), "ARTIFACT_HASH_MISMATCH", "/spec/artifactSha256");
    }

    void column(JsonNode column, VclType source, VclType target) {
        String path = "/spec/columns";
        VclException.require(source.kind().name().equals(column.path("sourceType").asText()), "SOURCE_TYPE_MISMATCH", path);
        VclException.require(source.unit().equals(column.path("sourceUnit").isNull() ? "1" : column.path("sourceUnit").asText()), "UNIT_MISMATCH", path);
        VclType current = source;
        for (var conversion : column.path("conversions")) current = conversion(current, conversion, path);
        target.compatible(current, path);
        VclException.require(!column.path("emptyValue").asText().equals("AS_NULL") || target.nullable(), "NULL_NOT_ALLOWED", path);
    }

    private VclType conversion(VclType input, JsonNode config, String path) {
        String method = config.path("method").asText();
        if (PARSERS.containsKey(method)) {
            VclExpressionTypes.require(input, VclType.Kind.TEXT, path);
            if (method.equals("parse_boolean")) {
                VclBooleanTokens.parse(config, path);
            }
            if (method.equals("parse_instant") && "YYYY-MM-DD HH:mm:ss".equals(config.path("format").asText())) {
                try { java.time.ZoneId.of(config.path("timezone").asText()); }
                catch (java.time.DateTimeException invalid) { throw new VclException("TIMEZONE_INVALID", path); }
            }
            if (method.equals("parse_duration")) {
                VclException.require(input.unit().equals("1") || input.unit().equals(config.path("sourceUnit").asText()), "UNIT_MISMATCH", path);
                return new VclType(VclType.Kind.DURATION, input.nullable(), "1", null);
            }
            if (!Set.of("parse_decimal", "parse_int64").contains(method)) {
                VclException.require(input.unit().equals("1"), "UNIT_MISMATCH", path);
            }
            return new VclType(PARSERS.get(method), input.nullable(), input.unit(), null);
        }
        if (method.equals("convert_unit")) {
            VclException.require(input.numeric(), "CONVERSION_TYPE_MISMATCH", path);
            String source = config.path("sourceUnit").asText();
            String target = config.path("targetUnit").asText();
            VclException.require(input.unit().equals(source), "IMPORT_UNIT_MISMATCH", path);
            VclException.require(units.require(source, path).dimension().equals(units.require(target, path).dimension()), "IMPORT_UNIT_MISMATCH", path);
            return new VclType(VclType.Kind.DECIMAL, input.nullable(), target, null);
        }
        if (method.equals("resolve_ref")) {
            var dataset = owner.dataset(config.path("target"), path);
            var field = VclExpressionTypes.field(owner.fields(dataset.path("fields"), path), config.path("keyFieldId").asText(), path);
            input.compatible(field, path);
            VclException.require(VclCompiler.strings(dataset.path("keyFieldIds")).equals(java.util.List.of(config.path("keyFieldId").asText())), "REF_AMBIGUOUS", path);
            return new VclType(VclType.Kind.REF, false, "1", "DATASET:" + config.at("/target/datasetId").asText());
        }
        VclException.require(method.equals("identity"), "METHOD_UNKNOWN", path);
        return input;
    }
}
