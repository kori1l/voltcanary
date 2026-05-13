package com.voltcanary.telemetry.dto;

import java.util.List;
import java.util.UUID;

public record TelemetryChartResponse(
        UUID deviceId,
        String metric,
        List<TelemetryChartPointResponse> points) {
}
