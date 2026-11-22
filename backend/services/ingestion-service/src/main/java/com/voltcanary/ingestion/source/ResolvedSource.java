package com.voltcanary.ingestion.source;

import java.util.UUID;

public record ResolvedSource(
    UUID accountId,
    UUID deviceId,
    UUID dataSourceId,
    String externalId,
    String sourceType,
    String protocol,
    String status,
    java.util.Map<String, Object> mapping
) {
    public ResolvedSource(
        UUID accountId,
        UUID deviceId,
        UUID dataSourceId,
        String externalId,
        String sourceType,
        String protocol,
        String status
    ) {
        this(
            accountId,
            deviceId,
            dataSourceId,
            externalId,
            sourceType,
            protocol,
            status,
            java.util.Map.of()
        );
    }
}
