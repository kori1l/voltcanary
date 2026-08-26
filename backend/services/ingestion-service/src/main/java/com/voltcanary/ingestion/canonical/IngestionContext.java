package com.voltcanary.ingestion.canonical;

import java.util.Map;

public record IngestionContext(
        String correlationId,
        String sourceToken,
        String clientIp,
        Map<String, String> metadata) {

    public IngestionContext {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static IngestionContext empty() {
        return new IngestionContext(null, null, null, Map.of());
    }
}
