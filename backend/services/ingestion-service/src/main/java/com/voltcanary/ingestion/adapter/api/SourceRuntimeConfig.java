package com.voltcanary.ingestion.adapter.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SourceRuntimeConfig(
        String sourceId,
        UUID dataSourceId,
        UUID accountId,
        UUID deviceId,
        AdapterType adapterType,
        ProtocolType protocol,
        TransportType transport,
        String status,
        String mappingSchemaVersion,
        String adapterConfigSchemaVersion,
        Map<String, Object> mapping,
        Map<String, Object> adapterConfig,
        Map<String, String> credentialReferences,
        String homeRegion,
        long version) {

    public SourceRuntimeConfig {
        sourceId = requireText(sourceId, "sourceId");
        status = requireText(status, "status");
        mappingSchemaVersion = optionalText(mappingSchemaVersion);
        adapterConfigSchemaVersion = optionalText(adapterConfigSchemaVersion);
        homeRegion = optionalText(homeRegion);
        Objects.requireNonNull(adapterType, "adapterType must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
        adapterConfig = adapterConfig == null ? Map.of() : Map.copyOf(adapterConfig);
        credentialReferences = credentialReferences == null ? Map.of() : Map.copyOf(credentialReferences);
    }

    public boolean active() {
        return "ACTIVE".equalsIgnoreCase(status);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
