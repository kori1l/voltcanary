package com.voltcanary.telemetry.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TelemetryEnvelopeResponse(UUID deviceId, String metric,
        List<TelemetryChartPointResponse> points, long rawPointCount,
        String aggregationMode, List<Bucket> buckets, Summary summary,
        Map<String, Long> qualityCounts) {
    public record Bucket(Instant from, Instant to, long count, double min, double max,
            Instant minAt, Instant maxAt, Instant firstAt, double firstValue,
            Instant lastAt, double lastValue, long gapCount) { }
    public record Summary(long count, Double min, Double max, Double average,
            Double p95, Double p99, Instant minAt, Instant maxAt,
            Instant lastAt, Double lastValue, long gapCount) { }
}
