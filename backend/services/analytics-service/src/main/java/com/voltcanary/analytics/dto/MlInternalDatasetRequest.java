package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.domain.MlModelFamily;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MlInternalDatasetRequest(
        @NotBlank String name,
        String description,
        @NotEmpty List<UUID> deviceIds,
        String deviceType,
        Map<String, String> deviceMetadata,
        @NotEmpty List<String> metrics,
        @NotNull Instant from,
        @NotNull Instant to,
        MlTrainingSplitStrategy splitStrategy,
        String splitConfigJson,
        String featureConfigVersion,
        @Positive Integer minSamplesPerMetric,
        @Positive Long maxSamplingGapSeconds,
        String previewId,
        String previewHash,
        MlModelFamily modelFamily,
        @Positive Integer horizonDays,
        MlMetricSelectionMode metricSelectionMode) {
}
