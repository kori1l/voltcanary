package com.voltcanary.aiagent.orchestration.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Provider subset derived from the canonical schema. Free JSON is explicitly JSON-encoded on the wire. */
public final class StrictOutputProjection {

    public static final String VERSION = "strict-projection-v2";
    private final ObjectMapper mapper;
    private final JsonNode canonical;

    public StrictOutputProjection(ObjectMapper mapper, JsonNode canonical) {
        this.mapper = mapper;
        this.canonical = canonical.deepCopy();
    }

    public JsonNode schema() {
        var root = mapper
            .createObjectNode()
            .put("type", "object")
            .put("additionalProperties", false);
        root.putArray("required").add("proposal");
        root.putObject("properties").set("proposal", project(canonical));
        // References remain rooted at the complete provider document.
        var proposal = (ObjectNode) root.path("properties").path("proposal");
        if (proposal.has("$defs")) root.set("$defs", proposal.remove("$defs"));
        return root;
    }

    public JsonNode normalize(JsonNode wire) {
        if (
            !wire.isObject() || wire.size() != 1 || !wire.has("proposal")
        ) throw new ModelRouting.Stop("STRICT_ENVELOPE_INVALID");
        return decode(canonical, wire.path("proposal"), 0);
    }

    private JsonNode project(JsonNode schema) {
        if (!schema.isObject()) throw new ModelRouting.Stop("STRICT_SCHEMA_UNSUPPORTED");
        var expanded = PlannerOutputVariants.expand(schema, mapper);
        if (expanded != schema) return project(expanded);
        if (free(schema)) return mapper
            .createObjectNode()
            .put("type", "string")
            .put(
                "description",
                "JSON-encoded value; decode once before canonical server validation. Do not put prose here."
            );
        var result = mapper.createObjectNode();
        for (String key : List.of("$ref", "type", "enum", "description", "minItems", "maxItems"))
            if (schema.has(key)) result.set(key, schema.get(key).deepCopy());
        if (schema.has("const")) {
            var constant = schema.get("const");
            result.put(
                "type",
                constant.isTextual()
                    ? "string"
                    : constant.isBoolean()
                      ? "boolean"
                      : constant.isNull()
                        ? "null"
                        : "number"
            );
            result.putArray("enum").add(constant);
        }
        for (String key : List.of("oneOf", "anyOf"))
            if (schema.has(key)) {
                var variants = result.putArray("anyOf");
                schema.path(key).forEach(child -> variants.add(project(child)));
            }
        if (schema.has("$defs")) {
            var defs = result.putObject("$defs");
            schema
                .path("$defs")
                .fields()
                .forEachRemaining(entry -> defs.set(entry.getKey(), project(entry.getValue())));
        }
        if (schema.has("properties") || schema.path("type").asText().equals("object")) {
            if (
                !schema.path("additionalProperties").isBoolean() ||
                schema.path("additionalProperties").asBoolean()
            ) throw new ModelRouting.Stop("STRICT_SCHEMA_UNSUPPORTED");
            result.put("type", "object").put("additionalProperties", false);
            var fields = result.putObject("properties");
            var required = result.putArray("required");
            schema
                .path("properties")
                .fields()
                .forEachRemaining(entry -> {
                    required.add(entry.getKey());
                    JsonNode value = project(entry.getValue());
                    if (!required(schema, entry.getKey())) {
                        var nullable = mapper.createObjectNode();
                        nullable
                            .putArray("anyOf")
                            .add(value)
                            .add(mapper.createObjectNode().put("type", "null"));
                        value = nullable;
                    }
                    fields.set(entry.getKey(), value);
                });
        }
        if (schema.has("items")) result.set("items", project(schema.get("items")));
        // Planner conditionals are compiled above; other numeric/string bounds retain canonical validation.
        if (result.isEmpty()) throw new ModelRouting.Stop("STRICT_SCHEMA_UNSUPPORTED");
        return result;
    }

    private boolean free(JsonNode schema) {
        return (
            !schema.has("$ref") &&
            !schema.has("oneOf") &&
            !schema.has("anyOf") &&
            !schema.has("const") &&
            (!schema.has("type") ||
                (schema.path("type").asText().equals("object") && !schema.has("properties")))
        );
    }

    private JsonNode decode(JsonNode schema, JsonNode value, int depth) {
        if (depth > 32) throw new ModelRouting.Stop("STRICT_OUTPUT_DEPTH");
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            if (!ref.startsWith("#/$defs/")) throw new ModelRouting.Stop(
                "STRICT_SCHEMA_UNSUPPORTED"
            );
            return decode(canonical.at(ref.substring(1)), value, depth + 1);
        }
        if (free(schema)) {
            if (!value.isTextual()) throw new ModelRouting.Stop("STRICT_JSON_VALUE_INVALID");
            try (var parser = mapper.getFactory().createParser(value.asText())) {
                parser.enable(
                    com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION
                );
                JsonNode decoded = mapper.readTree(parser);
                if (decoded == null || parser.nextToken() != null) throw new ModelRouting.Stop(
                    "STRICT_JSON_VALUE_INVALID"
                );
                return decoded;
            } catch (java.io.IOException invalid) {
                throw new ModelRouting.Stop("STRICT_JSON_VALUE_INVALID");
            }
        }
        for (String union : List.of("oneOf", "anyOf"))
            if (schema.has(union)) {
                for (var branch : schema.path(union))
                    if (matches(branch, value)) return decode(branch, value, depth + 1);
                throw new ModelRouting.Stop("STRICT_BRANCH_INVALID");
            }
        if (value.isObject() && schema.has("properties")) {
            var result = value.<ObjectNode>deepCopy();
            schema
                .path("properties")
                .fields()
                .forEachRemaining(entry -> {
                    String name = entry.getKey();
                    if (result.has(name)) {
                        if (result.path(name).isNull() && !required(schema, name)) result.remove(
                            name
                        );
                        else result.set(
                            name,
                            decode(entry.getValue(), result.path(name), depth + 1)
                        );
                    }
                });
            return result;
        }
        if (value.isArray() && schema.has("items")) {
            var result = mapper.createArrayNode();
            value.forEach(item -> result.add(decode(schema.path("items"), item, depth + 1)));
            return result;
        }
        return value.deepCopy();
    }

    private boolean matches(JsonNode schema, JsonNode value) {
        if (schema.has("$ref")) return matches(
            canonical.at(schema.path("$ref").asText().substring(1)),
            value
        );
        if (schema.has("const")) return schema.get("const").equals(value);
        if (schema.has("enum")) {
            for (var item : schema.path("enum")) if (item.equals(value)) return true;
            return false;
        }
        String type = schema.path("type").asText();
        if (type.equals("null")) return value.isNull();
        if (value.isNull()) return false;
        if (type.equals("object")) {
            if (!value.isObject()) return false;
            var fields = schema.path("properties").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (
                    (field.getValue().has("const") || field.getValue().has("enum")) &&
                    !matches(field.getValue(), value.path(field.getKey()))
                ) return false;
            }
        }
        return (
            !Set.of("string", "integer", "number", "boolean", "array").contains(type) ||
            (type.equals("string") && value.isTextual()) ||
            (Set.of("integer", "number").contains(type) && value.isNumber()) ||
            (type.equals("boolean") && value.isBoolean()) ||
            (type.equals("array") && value.isArray())
        );
    }

    private static boolean required(JsonNode schema, String name) {
        for (var field : schema.path("required")) if (field.asText().equals(name)) return true;
        return false;
    }
}
