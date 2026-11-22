package com.voltcanary.ingestion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TelemetryIngestionRequest(
    UUID deviceId,
    @NotBlank String sourceId,
    Instant timestamp,
    @NotEmpty Map<String, Object> metrics,
    com.voltcanary.common.events.telemetry.SourcePosition sourcePosition
) {
    public TelemetryIngestionRequest(
        UUID deviceId,
        String sourceId,
        Instant timestamp,
        Map<String, Object> metrics
    ) {
        this(deviceId, sourceId, timestamp, metrics, null);
    }
}
