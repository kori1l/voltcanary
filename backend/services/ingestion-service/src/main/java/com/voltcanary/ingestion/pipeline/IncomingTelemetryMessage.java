package com.voltcanary.ingestion.pipeline;

import java.time.Instant;
import java.util.Map;

public record IncomingTelemetryMessage(
        String sourceId,
        Instant timestamp,
        Map<String, Object> metrics,
        IngestionProtocol protocol,
        String rawPayload,
        String correlationId,
        String sourceToken,
        java.util.UUID deviceId,
        String clientIp,
        boolean historicalImport) {
}
