package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.List;

public record MlDatasetWorkbenchPreviewResponse(
        String previewId,
        String previewHash,
        Instant expiresAt,
        MlWorkbenchStatus status,
        String recommendedCandidateId,
        MlDatasetWindowDto selectedWindow,
        List<MlDatasetMetricCoverageDto> availableMetrics,
        List<MlDatasetCandidateWindowDto> candidateWindows,
        List<MlDatasetTimelineBucketDto> timeline,
        MlDatasetLabelReadinessDto labelReadiness,
        List<MlDatasetQualityGateDto> qualityGates,
        List<String> blockingReasons,
        List<String> warningReasons,
        MlDatasetPreviewCreateDefaultsDto createRequestDefaults) {
}
