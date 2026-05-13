package com.voltcanary.telemetry.dto;

import java.time.Instant;
import java.util.UUID;

public record TelemetryReadingResponse(
        UUID id,
        UUID deviceId,
        String sourceId,
        Instant recordedAt,
        Instant receivedAt,
        String metricsJson) {
}
