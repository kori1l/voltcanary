package com.voltcanary.telemetry.dto;

import java.time.Instant;
import java.util.UUID;

public record TelemetryQualityResponse(
        UUID id,
        UUID readingId,
        UUID deviceId,
        String flag,
        String message,
        Instant createdAt) {
}
