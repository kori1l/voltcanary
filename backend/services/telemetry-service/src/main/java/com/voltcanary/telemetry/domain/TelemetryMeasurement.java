package com.voltcanary.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

public record TelemetryMeasurement(
        UUID deviceId,
        String sourceId,
        Instant recordedAt,
        String metricName,
        double metricValue,
        String unit,
        QualityFlag quality,
        Instant receivedAt,
        Instant createdAt) {
}
