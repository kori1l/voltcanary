package com.voltcanary.alert.configurable.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Permission scope only: no source conversion, evaluator activation or notification authority. */
public record AutomationGrantScope(
    List<Resource> sourceBindings,
    List<Resource> datasets,
    Instant expiresAt
) {
    public static final String PROFILE = "source-native-fields-grant-1";

    public record Resource(UUID id, long schemaRevision, List<UUID> fieldIds) {
        public Resource {
            fieldIds = List.copyOf(fieldIds);
        }
    }

    public AutomationGrantScope {
        sourceBindings = List.copyOf(sourceBindings);
        datasets = List.copyOf(datasets);
    }

    public static AutomationGrantScope parse(JsonNode value) {
        fields(value, Set.of("sourceBindings", "datasets", "expiresAt"));
        Instant expiry;
        try {
            if (!value.path("expiresAt").isTextual()) throw new IllegalArgumentException();
            expiry = Instant.parse(value.path("expiresAt").asText());
            int year = expiry.atOffset(java.time.ZoneOffset.UTC).getYear();
            if (
                year < 1 || year > 9999 || expiry.getNano() % 1000 != 0
            ) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new VclException("GRANT_EXPIRY_INVALID", "/definition/expiresAt");
        }
        return new AutomationGrantScope(
            resources(value.path("sourceBindings"), true),
            resources(value.path("datasets"), false),
            expiry
        );
    }

    public JsonNode json() {
        var value = JsonNodeFactory.instance.objectNode().put("expiresAt", expiresAt.toString());
        write(value.putArray("sourceBindings"), sourceBindings);
        write(value.putArray("datasets"), datasets);
        return value;
    }

    private static void write(
        com.fasterxml.jackson.databind.node.ArrayNode output,
        List<Resource> resources
    ) {
        for (var resource : resources) {
            var value = output
                .addObject()
                .put("id", resource.id().toString())
                .put("schemaRevision", resource.schemaRevision());
            var ids = value.putArray("fieldIds");
            resource.fieldIds().forEach(id -> ids.add(id.toString()));
        }
    }

    private static List<Resource> resources(JsonNode values, boolean required) {
        if (
            !values.isArray() || values.size() > 16 || (required && values.isEmpty())
        ) throw new VclException("GRANT_SCOPE_INVALID", "/definition");
        var result = new ArrayList<Resource>();
        var ids = new HashSet<UUID>();
        for (var value : values) {
            fields(value, Set.of("id", "schemaRevision", "fieldIds"));
            UUID id = uuid(value.path("id"));
            if (
                !ids.add(id) ||
                !value.path("schemaRevision").isIntegralNumber() ||
                !value.path("schemaRevision").canConvertToLong() ||
                value.path("schemaRevision").asLong() < 1 ||
                !value.path("fieldIds").isArray() ||
                value.path("fieldIds").isEmpty() ||
                value.path("fieldIds").size() > 64
            ) throw new VclException("GRANT_SCOPE_INVALID", "/definition");
            var fieldIds = new HashSet<UUID>();
            for (var field : value.path("fieldIds"))
                if (!fieldIds.add(uuid(field))) throw new VclException(
                    "GRANT_SCOPE_INVALID",
                    "/definition"
                );
            result.add(
                new Resource(
                    id,
                    value.path("schemaRevision").asLong(),
                    fieldIds.stream().sorted().toList()
                )
            );
        }
        return result
            .stream()
            .sorted(java.util.Comparator.comparing(resource -> resource.id().toString()))
            .toList();
    }

    public static UUID uuid(JsonNode value) {
        try {
            if (!value.isTextual()) throw new IllegalArgumentException();
            UUID id = UUID.fromString(value.asText());
            if (
                !id.toString().equalsIgnoreCase(value.asText())
            ) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException failure) {
            throw new VclException("GRANT_SCOPE_INVALID", "");
        }
    }

    public static void fields(JsonNode value, Set<String> allowed) {
        var keys = new HashSet<String>();
        value.fieldNames().forEachRemaining(keys::add);
        if (!value.isObject() || !keys.equals(allowed)) throw new VclException(
            "REQUEST_FIELDS_INVALID",
            ""
        );
    }
}
