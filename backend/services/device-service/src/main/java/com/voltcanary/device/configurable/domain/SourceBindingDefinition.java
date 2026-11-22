package com.voltcanary.device.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryFieldPolicy;
import com.voltcanary.device.domain.DataSource;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclSourceBindingProfile;
import com.voltcanary.vcl.VclUnits;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Projects only explicit stored source fields. It cannot infer a field, unit, conversion or coverage. */
public final class SourceBindingDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SourceBindingDefinition() {}

    public static List<TelemetryFieldPolicy.Field> fields(DataSource source) {
        var mapping = object(source.getMappingJson());
        if (!mapping.has("telemetryPolicy")) return List.of();
        try {
            return MAPPER.treeToValue(mapping.path("telemetryPolicy"), TelemetryFieldPolicy.class)
                .fields()
                .stream()
                .filter(TelemetryFieldPolicy.Field::store)
                .toList();
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new VclException("BINDING_CONFIGURATION_INVALID", "");
        }
    }

    public static String configurationDigest(DataSource source, String processingDigest) {
        var value = MAPPER.createObjectNode()
            .put("profile", VclSourceBindingProfile.VERSION)
            .put("sourceId", source.getId().toString())
            .put("deviceId", source.getDeviceId().toString())
            .put("externalId", source.getSourceId())
            .put("protocol", source.getProtocol().name())
            .put("sourceType", source.getSourceType().name())
            .put("processingDigest", processingDigest)
            .put("unitRegistryDigest", VclUnits.standard().digest());
        // Legacy adapter JSON is not a VCL document: its numbers have no safe-JCS-number contract.
        // Pin the stored representation as text so distinct INT64/decimal values cannot alias.
        value.put("mappingJson", source.getMappingJson());
        value.put("adapterJson", source.getAdapterConfigJson());
        return VclCanonical.sha256(VclCanonical.encode(value));
    }

    public static JsonNode project(
        DataSource source,
        long revision,
        Map<String, UUID> ids,
        String configurationDigest
    ) {
        var definition = MAPPER.createObjectNode()
            .put("id", source.getId().toString())
            .put("schemaRevision", revision)
            .put("profile", VclSourceBindingProfile.VERSION)
            .put("configurationDigest", configurationDigest);
        var mappingConfig = object(source.getMappingJson());
        if (VclSourceBindingProfile.sampleHoldUs(mappingConfig).isPresent()) {
            definition.set(
                VclSourceBindingProfile.SAMPLE_HOLD,
                mappingConfig.path(VclSourceBindingProfile.SAMPLE_HOLD)
            );
        }
        if (VclSourceBindingProfile.intervalReport(mappingConfig).isPresent()) {
            definition.set(
                VclSourceBindingProfile.INTERVAL_REPORT,
                mappingConfig.path(VclSourceBindingProfile.INTERVAL_REPORT)
            );
        }
        if (VclSourceBindingProfile.occurrenceRouting(mappingConfig).isPresent()) {
            definition.set(
                VclSourceBindingProfile.OCCURRENCE_ROUTING,
                mappingConfig.path(VclSourceBindingProfile.OCCURRENCE_ROUTING)
            );
        }
        var fields = definition.putArray("fields");
        var mappings = definition.putArray("fieldMappings");
        var unsupported = definition.putArray("unsupportedFields");
        for (var field : fields(source)) {
            UUID id = ids.get(field.key());
            if (id == null) throw new VclException("BINDING_FIELD_IDENTITY_UNAVAILABLE", "");
            String type = switch (field.type()) {
                case "boolean" -> "BOOLEAN";
                case "string" -> "TEXT";
                case "point" -> "POINT";
                case "spatial_ref" -> "REF";
                case "instant" -> "INSTANT";
                default -> "DECIMAL";
            };
            boolean unitSupported = true;
            if (type.equals("DECIMAL")) {
                try {
                    VclUnits.standard().require(field.unit(), "");
                } catch (VclException failure) {
                    unitSupported = false;
                }
            }
            var mapping = mappings
                .addObject()
                .put("fieldId", id.toString())
                .put("sourceKey", field.key())
                .put("path", field.path())
                .put("sourceType", field.type())
                .put("sourceUnit", field.unit());
            // Legacy numbers have no pinned exact decimal conversion; retain that gap explicitly.
            if (!unitSupported || field.type().equals("number")) {
                unsupported
                    .addObject()
                    .put("fieldId", id.toString())
                    .put(
                        "reason",
                        unitSupported
                            ? "EXACT_NUMERIC_CONVERSION_REQUIRED"
                            : "UNIT_MAPPING_REQUIRED"
                    );
                mapping.put("vclReady", false);
                continue;
            }
            mapping.put("vclReady", true);
            var typed = fields
                .addObject()
                .put("id", id.toString())
                .put("label", field.label())
                .put("type", type)
                .put("nullable", true);
            if (type.equals("TEXT")) typed.put("maxLength", 512);
            if (type.equals("DECIMAL")) typed.put("unit", field.unit());
            if (type.equals("REF")) typed
                .putObject("target")
                .put("kind", "RESOURCE")
                .put("resourceKind", "SPATIAL_FEATURE");
        }
        VclSourceBindingProfile.intervalFields(definition);
        VclSourceBindingProfile.occurrenceFields(definition);
        return definition;
    }

    public static JsonNode capabilities() {
        return VclSourceBindingProfile.capabilities();
    }

    private static JsonNode object(String json) {
        if (json == null || json.isBlank()) return MAPPER.createObjectNode();
        try {
            var value = MAPPER.readTree(json);
            if (!value.isObject()) throw new VclException("BINDING_CONFIGURATION_INVALID", "");
            return value;
        } catch (java.io.IOException failure) {
            throw new VclException("BINDING_CONFIGURATION_INVALID", "");
        }
    }
}
