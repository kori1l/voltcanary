package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclUnits;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authoring projection of the existing /data form; symbolic keys never enter executable VCL. */
public final class NativeCollectionDraft {

    public static final String PROFILE = "native-table-authoring-1";
    private static final Set<String> TYPES = Set.of(
        "TEXT",
        "BOOLEAN",
        "INT64",
        "DECIMAL",
        "DATE",
        "INSTANT",
        "DURATION",
        "ENUM",
        "REF",
        "INTERVAL",
        "POINT"
    );
    private static final Set<String> KEY_TYPES = Set.of(
        "TEXT",
        "INT64",
        "DATE",
        "INSTANT",
        "ENUM",
        "REF"
    );
    private final JsonNode value;

    public NativeCollectionDraft(
        JsonNode proposal,
        ProtocolValidator validator,
        ObjectMapper mapper
    ) {
        validator.validateRegistered(schema(mapper), proposal);
        value = proposal.deepCopy();
        var keys = new HashSet<String>();
        int indexes = 0;
        int businessKeys = 0;
        for (var field : value.path("fields")) {
            require(keys.add(field.path("key").asText()), "NATIVE_FIELD_KEY_DUPLICATE");
            String type = field.path("type").asText();
            String unit = field.path("unit").asText();
            if (Set.of("INT64", "DECIMAL").contains(type)) VclUnits.standard().require(unit, "");
            else require(unit.equals("1"), "NATIVE_FIELD_UNIT_INVALID");
            require(field.has("choices") == type.equals("ENUM"), "NATIVE_FIELD_CHOICES_INVALID");
            require(field.has("reference") == type.equals("REF"), "NATIVE_FIELD_REFERENCE_INVALID");
            if (type.equals("ENUM")) choices(field);
            if (field.path("indexed").asBoolean()) {
                require(!Set.of("INTERVAL", "POINT").contains(type), "NATIVE_INDEX_TYPE_INVALID");
                indexes++;
            }
            if (field.path("businessKey").asBoolean()) {
                businessKeys++;
                require(
                    !field.path("nullable").asBoolean() && KEY_TYPES.contains(type),
                    "NATIVE_KEY_TYPE_INVALID"
                );
            }
        }
        require(indexes <= 8 && businessKeys <= 8, "NATIVE_INDEX_KEY_LIMIT");
    }

    public JsonNode value() {
        return value.deepCopy();
    }

    public List<String> fieldKeys() {
        return java.util.stream.StreamSupport.stream(value.path("fields").spliterator(), false)
            .map(field -> field.path("key").asText())
            .toList();
    }

    public VclDocument document(UUID datasetId, Map<String, UUID> fieldIds, ObjectMapper mapper) {
        require(
            fieldIds.keySet().equals(Set.copyOf(fieldKeys())) &&
                Set.copyOf(fieldIds.values()).size() == fieldIds.size(),
            "NATIVE_RESERVED_FIELDS_CHANGED"
        );
        var document = mapper.createObjectNode().put("language", "VCL/1").put("kind", "COLLECTION");
        var spec = document
            .putObject("spec")
            .put("id", datasetId.toString())
            .put("revision", 1)
            .put("label", value.path("label").asText());
        var fields = spec.putArray("fields");
        var keyIds = spec.putArray("keyFieldIds");
        var storage = spec
            .putObject("storage")
            .put("mode", "NATIVE")
            .put("history", value.path("history").asBoolean() ? "REVISIONS" : "NONE");
        var indexes = storage.putArray("indexedFieldIds");
        for (var field : value.path("fields")) {
            String id = fieldIds.get(field.path("key").asText()).toString();
            String type = field.path("type").asText();
            var target = fields
                .addObject()
                .put("id", id)
                .put("label", field.path("label").asText())
                .put("type", type)
                .put("nullable", field.path("nullable").asBoolean());
            if (type.equals("TEXT")) target.put("maxLength", 120);
            if (type.equals("DECIMAL")) target.put("precision", 18).put("scale", 3);
            if (Set.of("INT64", "DECIMAL").contains(type)) target.put(
                "unit",
                field.path("unit").asText()
            );
            if (type.equals("ENUM")) choices(field).forEach(target.putArray("values")::add);
            if (type.equals("REF")) {
                var reference = field.path("reference");
                if ("SELF".equals(reference.path("kind").asText())) {
                    target
                        .putObject("target")
                        .put("kind", "DATASET")
                        .put("datasetId", datasetId.toString());
                } else {
                    target.set("target", reference.deepCopy());
                }
            }
            if (field.path("businessKey").asBoolean()) keyIds.add(id);
            if (field.path("indexed").asBoolean()) indexes.add(id);
        }
        return new VclParser().parse(document.toString());
    }

    public static ObjectNode schema(ObjectMapper mapper) {
        var schema = mapper
            .createObjectNode()
            .put("type", "object")
            .put("additionalProperties", false);
        schema.putArray("required").add("label").add("fields").add("history");
        var properties = schema.putObject("properties");
        properties
            .putObject("label")
            .put("type", "string")
            .put("minLength", 1)
            .put("maxLength", 80);
        properties.putObject("history").put("type", "boolean");
        var field = properties
            .putObject("fields")
            .put("type", "array")
            .put("minItems", 1)
            .put("maxItems", 64)
            .putObject("items")
            .put("type", "object")
            .put("additionalProperties", false);
        field
            .putArray("required")
            .add("key")
            .add("label")
            .add("type")
            .add("nullable")
            .add("unit")
            .add("indexed")
            .add("businessKey");
        var fields = field.putObject("properties");
        fields
            .putObject("key")
            .put("type", "string")
            .put("pattern", "^[A-Za-z][A-Za-z0-9_]{0,63}$");
        fields.putObject("label").put("type", "string").put("minLength", 1).put("maxLength", 80);
        var types = fields.putObject("type").put("type", "string").putArray("enum");
        TYPES.stream().sorted().forEach(types::add);
        fields.putObject("unit").put("type", "string").put("minLength", 1).put("maxLength", 32);
        fields
            .putObject("choices")
            .put("type", "string")
            .put("minLength", 1)
            .put("maxLength", 16448);
        var variants = fields.putObject("reference").putArray("oneOf");
        referenceVariant(variants.addObject(), "SELF", null);
        var dataset = referenceVariant(variants.addObject(), "DATASET", "datasetId");
        dataset
            .putObject("datasetId")
            .put("type", "string")
            .put("pattern", "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        var resource = referenceVariant(variants.addObject(), "RESOURCE", "resourceKind");
        resource
            .putObject("resourceKind")
            .put("type", "string")
            .putArray("enum")
            .add("DEVICE")
            .add("SOURCE")
            .add("LOCATION")
            .add("VARIABLE")
            .add("SCHEDULE")
            .add("OPERATIONAL_FACT")
            .add("SPATIAL_FEATURE");
        for (String flag : List.of("nullable", "indexed", "businessKey"))
            fields.putObject(flag).put("type", "boolean");
        return schema;
    }

    private static ObjectNode referenceVariant(ObjectNode variant, String kind, String property) {
        variant.put("type", "object").put("additionalProperties", false);
        var required = variant.putArray("required").add("kind");
        if (property != null) required.add(property);
        var properties = variant.putObject("properties");
        properties.putObject("kind").put("const", kind);
        return properties;
    }

    private static List<String> choices(JsonNode field) {
        var values = java.util.Arrays.stream(field.path("choices").textValue().split("\\r?\\n", -1))
            .filter(value -> !value.isBlank())
            .toList();
        require(
            !values.isEmpty() &&
                values.size() <= 64 &&
                new HashSet<>(values).size() == values.size() &&
                values.stream().allMatch(value -> value.codePointCount(0, value.length()) <= 256),
            "NATIVE_FIELD_CHOICES_INVALID"
        );
        return values;
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }

    @Override
    public String toString() {
        return "NativeCollectionDraft[profile=" + PROFILE + "]";
    }
}
