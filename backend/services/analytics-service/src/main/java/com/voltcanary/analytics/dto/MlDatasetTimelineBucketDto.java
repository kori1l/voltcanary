package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.List;

public record MlDatasetTimelineBucketDto(
        Instant bucketStart,
        Instant bucketEnd,
        long sampleCount,
        double coverageRatio,
        MlWorkbenchStatus status,
        List<String> events) {
}
