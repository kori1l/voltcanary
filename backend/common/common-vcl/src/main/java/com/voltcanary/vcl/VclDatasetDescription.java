package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;

/** Owner-issued metadata transport. Deserialization alone is not authorization. */
public record VclDatasetDescription(UUID id, long schemaRevision, JsonNode definition, String schemaDigest,
        String accessScopeDigest, UUID generation, long dataVersion, Set<UUID> readyIndexedFields,
        String storageProfile, boolean liveQueryReady, boolean historyReady, JsonNode queryCapabilities) {
    public VclDatasetDescription {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(generation);
        if (schemaRevision < 1 || dataVersion < 1 || !schemaDigest.matches("[0-9a-f]{64}") || !accessScopeDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid dataset snapshot");
        }
        definition = definition.deepCopy();
        queryCapabilities = queryCapabilities.deepCopy();
        readyIndexedFields = Set.copyOf(readyIndexedFields);
    }

    @Override public JsonNode definition() { return definition.deepCopy(); }
    @Override public JsonNode queryCapabilities() { return queryCapabilities.deepCopy(); }
    @Override public String toString() { return "VclDatasetDescription[id=" + id + ", schemaRevision=" + schemaRevision + "]"; }
}
