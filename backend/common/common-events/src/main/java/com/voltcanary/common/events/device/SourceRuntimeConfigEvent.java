package com.voltcanary.common.events.device;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SourceRuntimeConfigEvent(
        UUID eventId,
        String eventVersion,
        String sourceId,
        UUID dataSourceId,
        UUID accountId,
        UUID deviceId,
        String adapterType,
        String protocol,
        String transport,
        String status,
        String mappingSchemaVersion,
        String adapterConfigSchemaVersion,
        Map<String, Object> mapping,
        Map<String, Object> adapterConfig,
        Map<String, String> credentialReferences,
        String tokenPrefix,
        String tokenHash,
        String homeRegion,
        long configVersion,
        Instant occurredAt,
        String correlationId,
        String producer) {

    public SourceRuntimeConfigEvent {
        mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
        adapterConfig = adapterConfig == null ? Map.of() : Map.copyOf(adapterConfig);
        credentialReferences = credentialReferences == null ? Map.of() : Map.copyOf(credentialReferences);
    }
}
