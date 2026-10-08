package com.voltcanary.telemetry.dto;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonInclude;

public record TelemetryChartPointResponse(
        Instant timestamp,
        double value,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String quality) {
    public TelemetryChartPointResponse(Instant timestamp, double value) { this(timestamp, value, null, null); }
}
