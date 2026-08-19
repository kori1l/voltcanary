package com.voltcanary.analytics.service;

public record MlDatasetNormalizedMetric(
        String rawMetricName,
        String canonicalMetricName,
        String canonicalUnit) {

    public String rawUnit() {
        return canonicalUnit;
    }
}
