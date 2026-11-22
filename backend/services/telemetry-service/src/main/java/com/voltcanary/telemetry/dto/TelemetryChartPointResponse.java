package com.voltcanary.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

public record TelemetryChartPointResponse(
    Instant timestamp,
    double value,
    @JsonInclude(JsonInclude.Include.NON_NULL) String sourceId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String quality
) {
    public TelemetryChartPointResponse(Instant timestamp, double value) {
        this(timestamp, value, null, null);
    }
}
