package com.voltcanary.ingestion.pipeline;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ResolvedTelemetryMessage(
        UUID accountId,
        UUID deviceId,
        UUID dataSourceId,
        String externalSourceId,
        Instant timestamp,
        Map<String, Object> metrics,
        IngestionProtocol protocol,
        String correlationId) {
}
