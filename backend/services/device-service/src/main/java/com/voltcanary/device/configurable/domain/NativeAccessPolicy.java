package com.voltcanary.device.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** One coherent grant: field and row scopes are never cross-multiplied across different grants. */
public record NativeAccessPolicy(
    Set<UUID> readFields,
    Set<UUID> writeFields,
    boolean allRows,
    Set<UUID> recordIds,
    Set<String> actions
) {
    public NativeAccessPolicy {
        readFields = Set.copyOf(readFields);
        writeFields = Set.copyOf(writeFields);
        recordIds = Set.copyOf(recordIds);
        actions = Set.copyOf(actions);
        require(
            !readFields.isEmpty() && readFields.size() <= 64 && readFields.containsAll(writeFields),
            "NATIVE_SCOPE_INVALID"
        );
        require(
            recordIds.size() <= 500 && (!allRows || recordIds.isEmpty()),
            "NATIVE_SCOPE_INVALID"
        );
        require(
            actions.contains("READ") &&
                Set.of("READ", "HISTORY", "INSERT", "UPDATE", "DELETE").containsAll(actions),
            "NATIVE_SCOPE_INVALID"
        );
        require(!actions.contains("INSERT") || allRows, "NATIVE_SCOPE_INVALID");
        require(
            !(actions.contains("INSERT") || actions.contains("UPDATE")) || !writeFields.isEmpty(),
            "NATIVE_SCOPE_INVALID"
        );
    }

    public static NativeAccessPolicy parse(JsonNode value) {
        closed(value, Set.of("readFieldIds", "writeFieldIds", "rows", "actions"));
        var rows = value.path("rows");
        closed(rows, Set.of("mode", "recordIds"));
        require(
            rows.path("mode").isTextual() &&
                Set.of("ALL", "IDS").contains(rows.path("mode").asText()),
            "NATIVE_SCOPE_INVALID"
        );
        var actions = value.path("actions");
        require(
            actions.isArray() && actions.size() >= 1 && actions.size() <= 5,
            "NATIVE_SCOPE_INVALID"
        );
        var selected = new LinkedHashSet<String>();
        for (var action : actions)
            require(action.isTextual() && selected.add(action.textValue()), "NATIVE_SCOPE_INVALID");
        return new NativeAccessPolicy(
            ids(value.path("readFieldIds"), 64),
            ids(value.path("writeFieldIds"), 64),
            rows.path("mode").asText().equals("ALL"),
            ids(rows.path("recordIds"), 500),
            selected
        );
    }

    public void validate(NativeDataset dataset) {
        require(!actions.contains("HISTORY") || dataset.schema().history(), "NATIVE_SCOPE_INVALID");
        for (UUID id : readFields) dataset.schema().field(id);
        if (actions.contains("INSERT")) {
            require(writeFields.containsAll(dataset.schema().keys()), "NATIVE_SCOPE_INVALID");
            for (var field : dataset.schema().fields()) {
                UUID id = UUID.fromString(field.path("id").asText());
                require(
                    dataset.schema().type(id).nullable() || writeFields.contains(id),
                    "NATIVE_SCOPE_INVALID"
                );
            }
        }
    }

    public boolean permits(UUID record) {
        return allRows || recordIds.contains(record);
    }

    public JsonNode json() {
        var output = JsonNodeFactory.instance.objectNode();
        var reads = output.putArray("readFieldIds");
        readFields
            .stream()
            .sorted()
            .forEach(id -> reads.add(id.toString()));
        var writes = output.putArray("writeFieldIds");
        writeFields
            .stream()
            .sorted()
            .forEach(id -> writes.add(id.toString()));
        var rows = output
            .putObject("rows")
            .put("mode", allRows ? "ALL" : "IDS")
            .putArray("recordIds");
        recordIds
            .stream()
            .sorted()
            .forEach(id -> rows.add(id.toString()));
        var operations = output.putArray("actions");
        actions.stream().sorted().forEach(operations::add);
        return output;
    }

    private static Set<UUID> ids(JsonNode values, int limit) {
        require(values.isArray() && values.size() <= limit, "NATIVE_SCOPE_INVALID");
        var result = new LinkedHashSet<UUID>();
        for (var value : values) {
            try {
                require(value.isTextual(), "NATIVE_SCOPE_INVALID");
                UUID id = UUID.fromString(value.textValue());
                require(
                    id.toString().equals(value.textValue()) && result.add(id),
                    "NATIVE_SCOPE_INVALID"
                );
            } catch (IllegalArgumentException invalid) {
                throw new VclException("NATIVE_SCOPE_INVALID", "");
            }
        }
        return Set.copyOf(result);
    }

    private static void closed(JsonNode value, Set<String> fields) {
        require(
            value != null &&
                value.isObject() &&
                value.size() == fields.size() &&
                fields.stream().allMatch(value::has),
            "NATIVE_SCOPE_INVALID"
        );
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
