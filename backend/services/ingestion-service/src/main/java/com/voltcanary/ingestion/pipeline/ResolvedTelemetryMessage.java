package com.voltcanary.ingestion.pipeline;

import com.voltcanary.ingestion.adapter.api.ProtocolType;
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
        ProtocolType protocol,
        String correlationId) {
}
