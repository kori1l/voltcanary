package com.voltcanary.analytics.telemetry;

public record TelemetryMetricResponse(
        String name,
        String unit,
        String type,
        double min,
        double max,
        String displayName) {
}
