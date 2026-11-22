package com.voltcanary.telemetry.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.common.events.telemetry.ConfiguredFields;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.vcl.VclSourceBindingProfile;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Projects explicit typed source fields without inferring conversions. */
public final class ObservationProjection {

    private static final com.voltcanary.vcl.VclUnits UNITS = com.voltcanary.vcl.VclUnits.standard();

    private ObservationProjection() {}

    public static void validateSchema(JsonNode definition) {
        var schemaFields = new HashSet<>(
            Set.of(
                "id",
                "schemaRevision",
                "profile",
                "configurationDigest",
                "fields",
                "fieldMappings",
                "unsupportedFields"
            )
        );
        if (VclSourceBindingProfile.sampleHoldUs(definition).isPresent()) schemaFields.add(
            VclSourceBindingProfile.SAMPLE_HOLD
        );
        if (VclSourceBindingProfile.intervalFields(definition).isPresent()) schemaFields.add(
            VclSourceBindingProfile.INTERVAL_REPORT
        );
        if (VclSourceBindingProfile.occurrenceFields(definition).isPresent()) schemaFields.add(
            VclSourceBindingProfile.OCCURRENCE_ROUTING
        );
        fields(definition, schemaFields);
        if (
            !VclSourceBindingProfile.VERSION.equals(definition.path("profile").asText()) ||
            !definition.path("configurationDigest").asText().matches("[0-9a-f]{64}") ||
            !definition.path("fields").isArray() ||
            definition.path("fields").size() > 32 ||
            !definition.path("fieldMappings").isArray() ||
            definition.path("fieldMappings").size() > 32 ||
            !definition.path("unsupportedFields").isArray() ||
            definition.path("unsupportedFields").size() > 32
        ) invalid();
        var ids = new HashSet<UUID>();
        var keys = new HashSet<String>();
        var mapped = new HashSet<UUID>();
        var unsupported = new HashSet<UUID>();
        for (var field : definition.path("fields")) {
            String type = field.path("type").asText();
            fields(
                field,
                switch (type) {
                    case "TEXT" -> Set.of("id", "label", "type", "nullable", "maxLength");
                    case "DECIMAL" -> Set.of("id", "label", "type", "nullable", "unit");
                    case "REF" -> Set.of("id", "label", "type", "nullable", "target");
                    default -> Set.of("id", "label", "type", "nullable");
                }
            );
            UUID id = uuid(field.path("id"));
            if (
                !ids.add(id) ||
                !Set.of("BOOLEAN", "TEXT", "DECIMAL", "POINT", "REF", "INSTANT").contains(type) ||
                !ConfiguredFields.text(field.path("label").textValue(), 80) ||
                field.path("label").asText().isBlank() ||
                !field.path("nullable").isBoolean() ||
                !field.path("nullable").asBoolean() ||
                (field.path("type").asText().equals("TEXT") &&
                    (!field.path("maxLength").isIntegralNumber() ||
                        field.path("maxLength").asLong() != 512))
            ) invalid();
            if (type.equals("DECIMAL")) {
                try {
                    UNITS.require(field.path("unit").textValue(), "");
                } catch (RuntimeException invalidUnit) {
                    invalid();
                }
            }
            if (type.equals("REF")) {
                var target = field.path("target");
                fields(target, Set.of("kind", "resourceKind"));
                if (
                    !"RESOURCE".equals(target.path("kind").textValue()) ||
                    !"SPATIAL_FEATURE".equals(target.path("resourceKind").textValue())
                ) invalid();
            }
        }
        for (var field : definition.path("unsupportedFields")) {
            fields(field, Set.of("fieldId", "reason"));
            UUID id = uuid(field.path("fieldId"));
            if (
                !unsupported.add(id) ||
                ids.contains(id) ||
                !Set.of("EXACT_NUMERIC_CONVERSION_REQUIRED", "UNIT_MAPPING_REQUIRED").contains(
                    field.path("reason").asText()
                )
            ) invalid();
        }
        var allMappings = new HashSet<UUID>();
        for (var mapping : definition.path("fieldMappings")) {
            fields(
                mapping,
                Set.of("fieldId", "sourceKey", "path", "sourceType", "sourceUnit", "vclReady")
            );
            UUID id = uuid(mapping.path("fieldId"));
            String key = mapping.path("sourceKey").asText();
            if (
                !allMappings.add(id) ||
                !mapping.path("vclReady").isBoolean() ||
                !keys.add(key) ||
                !key.matches("[A-Za-z][A-Za-z0-9_.]{0,63}") ||
                !mapping
                    .path("path")
                    .asText()
                    .matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){0,3}") ||
                !ConfiguredFields.text(mapping.path("sourceUnit").textValue(), 24)
            ) invalid();
            if (!mapping.path("vclReady").asBoolean()) {
                if (
                    !unsupported.contains(id) ||
                    !Set.of("number", "decimal").contains(mapping.path("sourceType").asText())
                ) invalid();
                continue;
            }
            if (!ids.contains(id) || !mapped.add(id)) invalid();
            var field = java.util.stream.StreamSupport.stream(
                definition.path("fields").spliterator(),
                false
            )
                .filter(value -> value.path("id").asText().equals(id.toString()))
                .findFirst()
                .orElseThrow();
            String expected = switch (field.path("type").asText()) {
                case "BOOLEAN" -> "boolean";
                case "DECIMAL" -> "decimal";
                case "POINT" -> "point";
                case "REF" -> "spatial_ref";
                case "INSTANT" -> "instant";
                default -> "string";
            };
            if (!mapping.path("sourceType").asText().equals(expected)) invalid();
            if (
                "decimal".equals(expected) && !field.path("unit").equals(mapping.path("sourceUnit"))
            ) invalid();
            if (
                Set.of("point", "spatial_ref", "instant").contains(expected) &&
                !"1".equals(mapping.path("sourceUnit").textValue())
            ) invalid();
        }
        var expectedMappings = new HashSet<>(ids);
        expectedMappings.addAll(unsupported);
        if (!ids.equals(mapped) || !expectedMappings.equals(allMappings)) invalid();
    }

    public static JsonNode project(TelemetryReceivedEvent event, JsonNode definition) {
        validateSchema(definition);
        var configured = ConfiguredFields.values(event.metrics());
        var values = JsonNodeFactory.instance.arrayNode();
        var occurrence = VclSourceBindingProfile.occurrenceFields(definition)
            .map(contract ->
                Set.of(contract.subject().toString(), contract.occurrence().toString())
            )
            .orElseGet(Set::of);
        for (var field : definition.path("fields")) {
            String id = field.path("id").asText();
            var mapping = java.util.stream.StreamSupport.stream(
                definition.path("fieldMappings").spliterator(),
                false
            )
                .filter(value -> value.path("fieldId").asText().equals(id))
                .findFirst()
                .orElseThrow();
            var descriptor = configured.get(mapping.path("sourceKey").asText());
            Object value = descriptor == null ? null : descriptor.get("value");
            String type = field.path("type").asText();
            var output = values.addObject().put("fieldId", id).put("type", type);
            if ("DECIMAL".equals(type)) output.put("unit", field.path("unit").textValue());
            if ("REF".equals(type)) output.set("target", field.path("target"));
            if (
                value == null ||
                (occurrence.contains(id) && value instanceof String text && text.isBlank())
            ) {
                output
                    .put("knowledge", "UNKNOWN")
                    .put("reason", "MISSING_OR_NULL_INPUT")
                    .putNull("value");
            } else {
                if (
                    !mapping.path("sourceType").asText().equals(descriptor.get("type")) ||
                    (type.equals("BOOLEAN") && !(value instanceof Boolean)) ||
                    (type.equals("TEXT") &&
                        (!(value instanceof String text) || !ConfiguredFields.text(text, 512))) ||
                    (type.equals("DECIMAL") &&
                        !field.path("unit").textValue().equals(descriptor.get("unit")))
                ) {
                    throw new ObservationCaptureUnavailable("OBSERVATION_VALUE_INVALID");
                }
                output.put("knowledge", "KNOWN").putNull("reason");
                if (value instanceof Boolean bool) output.put("value", bool);
                else if ("POINT".equals(type)) {
                    var point = ConfiguredFields.canonicalPoint(value);
                    output
                        .putObject("value")
                        .put("longitude", point.get("longitude"))
                        .put("latitude", point.get("latitude"));
                } else output.put("value", (String) value);
            }
        }
        VclSourceBindingProfile.intervalFields(definition).ifPresent(contract -> {
            var start = intervalBound(values, contract.start());
            var end = intervalBound(values, contract.end());
            if (
                start == null ||
                end == null ||
                !start.isBefore(end) ||
                !end.equals(event.timestamp()) ||
                java.time.Duration.between(start, end).compareTo(
                    java.time.Duration.of(
                        contract.maxSpanUs(),
                        java.time.temporal.ChronoUnit.MICROS
                    )
                ) > 0
            ) {
                throw new ObservationCaptureUnavailable("OBSERVATION_INTERVAL_REPORT_INVALID");
            }
        });
        return values;
    }

    private static java.time.Instant intervalBound(JsonNode values, UUID field) {
        for (var value : values) {
            if (
                field.toString().equals(value.path("fieldId").textValue()) &&
                "KNOWN".equals(value.path("knowledge").textValue())
            ) {
                return java.time.Instant.parse(value.path("value").textValue());
            }
        }
        return null;
    }

    private static UUID uuid(JsonNode node) {
        try {
            var id = UUID.fromString(node.textValue());
            if (!id.toString().equals(node.textValue())) invalid();
            return id;
        } catch (RuntimeException failure) {
            throw new ObservationCaptureUnavailable("OBSERVATION_SCHEMA_INVALID");
        }
    }

    private static void fields(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(expected)) invalid();
    }

    private static void invalid() {
        throw new ObservationCaptureUnavailable("OBSERVATION_SCHEMA_INVALID");
    }
}
