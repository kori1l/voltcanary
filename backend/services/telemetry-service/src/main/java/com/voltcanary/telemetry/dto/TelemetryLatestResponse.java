package com.voltcanary.telemetry.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TelemetryLatestResponse(
        UUID deviceId,
        Map<String, Double> readings,
        Instant timestamp) {
}
