package com.voltcanary.analytics.service;

import java.time.Instant;
import java.util.UUID;

public record MlDatasetTelemetryRow(
        UUID deviceId,
        String metricName,
        String canonicalMetricName,
        String rawUnit,
        String canonicalUnit,
        Instant timestamp,
        double numericValue) {
}
