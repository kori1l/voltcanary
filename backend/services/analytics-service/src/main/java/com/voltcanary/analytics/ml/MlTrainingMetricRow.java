package com.voltcanary.analytics.ml;

import java.time.Instant;
import java.util.Map;

public record MlTrainingMetricRow(
        String timestamp,
        String deviceId,
        String metric,
        double value,
        String unit,
        String label,
        Map<String, Object> metadata) {

    public MlTrainingMetricRow(Instant timestamp, String deviceId, String metric, double value, Map<String, Object> metadata) {
        this(timestamp == null ? null : timestamp.toString(), deviceId, metric, value, null, null, metadata);
    }
}
