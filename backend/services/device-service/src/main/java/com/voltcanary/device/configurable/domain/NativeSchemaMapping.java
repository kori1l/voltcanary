package com.voltcanary.device.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Explicit, statically checked migration of values; it has no storage or authority effects. */
public final class NativeSchemaMapping {
    private static final VclParser PARSER = new VclParser();
    private static final VclCompiler COMPILER = new VclCompiler(new VclRegistry(PARSER), VclUnits.standard());
    private final Map<UUID, JsonNode> mappings;

    private NativeSchemaMapping(Map<UUID, JsonNode> mappings) {
        this.mappings = Map.copyOf(mappings);
    }

    public static NativeSchemaMapping compile(NativeSchema previous, NativeSchema next, JsonNode declarations,
            Set<UUID> reservedFields, VclMetadata metadata) {
        require(previous.id().equals(next.id()) && previous.revision() < 9_007_199_254_740_991L
                && next.revision() == previous.revision() + 1, "SCHEMA_REVISION_CONFLICT");
        require(declarations.isArray() && declarations.size() <= 64, "MIGRATION_MAPPING_INVALID");
        var oldFields = new LinkedHashMap<UUID, JsonNode>();
        previous.fields().forEach(field -> oldFields.put(UUID.fromString(field.path("id").asText()), field));
        var selected = new LinkedHashMap<UUID, JsonNode>();
        for (var mapping : declarations) {
            require(mapping.isObject(), "MIGRATION_MAPPING_INVALID");
            boolean constant = mapping.has("literal");
            closed(mapping, constant ? Set.of("targetFieldId", "literal")
                    : Set.of("targetFieldId", "sourceFieldId", "column"));
            UUID target = uuid(mapping.path("targetFieldId"));
            var targetField = next.field(target);
            require(selected.putIfAbsent(target, mapping.deepCopy()) == null, "MIGRATION_MAPPING_DUPLICATE");
            if (constant) {
                var literal = PARSER.literal(mapping.path("literal").toString());
                if (literal.path("type").asText().equals("NULL")) {
                    require(next.type(target).nullable(), "NULL_NOT_ALLOWED");
                } else {
                    next.type(target).compatible(com.voltcanary.vcl.VclType.from(literal), "");
                }
            } else {
                UUID source = uuid(mapping.path("sourceFieldId"));
                var sourceField = oldFields.get(source);
                require(sourceField != null, "FIELD_NOT_IN_SCOPE");
                var column = mapping.path("column");
                require(column.path("sourceColumn").asText().equals(source.toString()), "MIGRATION_MAPPING_INVALID");
                COMPILER.checkImportColumn(column, previous.type(source), targetField, metadata);
            }
        }
        for (var field : next.fields()) {
            UUID id = UUID.fromString(field.path("id").asText());
            var old = oldFields.get(id);
            if (old == null) {
                require(reservedFields.contains(id), "FIELD_NOT_RESERVED");
                // Even a new nullable field needs an explicit fill policy.
                require(selected.containsKey(id), "MIGRATION_MAPPING_REQUIRED");
            } else {
                var before = previous.type(id);
                var after = next.type(id);
                require(before.kind() == after.kind() && before.unit().equals(after.unit())
                        && Objects.equals(before.referenceTarget(), after.referenceTarget()), "FIELD_ID_MEANING_CHANGED");
                if (!selected.containsKey(id)) {
                    var mapping = JsonNodeFactory.instance.objectNode().put("targetFieldId", id.toString())
                            .put("sourceFieldId", id.toString());
                    selected.put(id, mapping);
                }
            }
        }
        return new NativeSchemaMapping(selected);
    }

    /** Source literals were decoded by the old schema. Final values still require target row validation. */
    public Map<UUID, JsonNode> convert(Map<UUID, JsonNode> source, NativeImportInput.ReferenceResolver references) {
        var result = new LinkedHashMap<UUID, JsonNode>();
        for (var entry : mappings.entrySet()) {
            var mapping = entry.getValue();
            JsonNode value;
            if (mapping.has("literal")) {
                value = mapping.path("literal");
            } else {
                UUID sourceField = uuid(mapping.path("sourceFieldId"));
                var literal = source.get(sourceField);
                require(literal != null, "MIGRATION_SOURCE_INCOMPLETE");
                if (mapping.has("column")) {
                    var row = JsonNodeFactory.instance.objectNode();
                    row.set(sourceField.toString(), literal.path("value"));
                    value = NativeImportInput.convert(row, mapping.path("column"), references);
                } else {
                    value = literal;
                }
            }
            result.put(entry.getKey(), value.deepCopy());
        }
        return Map.copyOf(result);
    }

    /** Only actual conversion columns participate in batch reference resolution. */
    public JsonNode columns() {
        var result = JsonNodeFactory.instance.arrayNode();
        mappings.values().forEach(mapping -> {
            if (mapping.has("column")) result.add(mapping.path("column").deepCopy());
        });
        return result;
    }

    private static void closed(JsonNode value, Set<String> fields) {
        var actual = new java.util.HashSet<String>();
        value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(fields), "MIGRATION_MAPPING_INVALID");
    }

    private static UUID uuid(JsonNode value) {
        try {
            require(value.isTextual(), "MIGRATION_MAPPING_INVALID");
            UUID id = UUID.fromString(value.textValue());
            require(id.toString().equals(value.textValue()), "MIGRATION_MAPPING_INVALID");
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new VclException("MIGRATION_MAPPING_INVALID", "");
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
