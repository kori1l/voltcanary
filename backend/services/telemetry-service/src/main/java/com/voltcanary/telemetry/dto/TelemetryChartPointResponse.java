package com.voltcanary.telemetry.dto;

import java.time.Instant;

public record TelemetryChartPointResponse(
        Instant timestamp,
        double value) {
}
