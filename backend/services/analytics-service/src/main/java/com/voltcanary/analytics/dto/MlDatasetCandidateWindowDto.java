package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.List;

public record MlDatasetCandidateWindowDto(
        String id,
        MlCandidateWindowKind kind,
        Instant from,
        Instant to,
        double score,
        MlWorkbenchStatus status,
        boolean recommended,
        long sampleCount,
        int deviceCount,
        int metricCount,
        double coverageRatio,
        long maxSamplingGapSeconds,
        long positiveLabels,
        long normalLabels,
        long unobservableLabels,
        long maintenanceExcludedLabels,
        List<String> blockingReasons,
        List<String> warningReasons,
        String explanation) {
}
