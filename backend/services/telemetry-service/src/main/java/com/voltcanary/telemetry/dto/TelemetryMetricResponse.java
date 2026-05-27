package com.voltcanary.telemetry.dto;

public record TelemetryMetricResponse(
        String name,
        String unit,
        String type,
        double min,
        double max,
        String displayName) {
}
