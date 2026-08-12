package com.voltcanary.analytics.dto;

import java.util.List;

public record MlDatasetMetricCoverageDto(
        String metricName,
        String displayName,
        String canonicalMetricName,
        String unit,
        int devicesWithMetric,
        int selectedDeviceCount,
        double coverageRatio,
        long sampleCount,
        long maxSamplingGapSeconds,
        double duplicateTimestampRate,
        long nonFiniteImpossibleValueCount,
        long unitNormalizationWarnings,
        MlWorkbenchStatus status,
        List<String> blockingReasons,
        List<String> warningReasons) {
}
