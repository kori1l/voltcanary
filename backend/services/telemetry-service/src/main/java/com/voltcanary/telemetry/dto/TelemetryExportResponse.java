package com.voltcanary.telemetry.dto;

import java.util.List;
import java.util.UUID;

public record TelemetryExportResponse(
        UUID deviceId,
        String metric,
        List<TelemetryChartPointResponse> points,
        String nextCursor,
        String nextCursorSourceId) {
}
