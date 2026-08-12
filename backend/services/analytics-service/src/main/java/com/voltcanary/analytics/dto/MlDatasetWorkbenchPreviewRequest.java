package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MlDatasetWorkbenchPreviewRequest(
        MlModelFamily modelFamily,
        @NotEmpty List<UUID> deviceIds,
        String deviceType,
        Map<String, String> deviceMetadata,
        MlMetricSelectionMode metricSelectionMode,
        List<String> selectedMetrics,
        @NotNull Instant from,
        @NotNull Instant to,
        String quickRange,
        Integer horizonDays,
        MlTrainingSplitStrategy splitStrategy,
        String splitConfigJson,
        String featureConfigVersion,
        Integer minSamplesPerMetric,
        Long maxSamplingGapSeconds,
        Integer candidateLimit) {
}
