package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.UUID;

public record BaselineResponse(
        UUID deviceId,
        String metric,
        String period,
        double mean,
        double median,
        double p90,
        double min,
        double max,
        int sampleCount,
        Instant calculatedAt) {
}
