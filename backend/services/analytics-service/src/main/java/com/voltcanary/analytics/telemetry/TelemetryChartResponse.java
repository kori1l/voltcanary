package com.voltcanary.analytics.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TelemetryChartResponse(
        UUID deviceId,
        String metric,
        List<Point> points) {

    public record Point(Instant timestamp, double value) {
    }
}
