package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import java.time.Instant;
import java.util.List;

public record MlDatasetPreviewCreateDefaultsDto(
        List<String> metrics,
        Instant from,
        Instant to,
        MlTrainingSplitStrategy splitStrategy,
        String featureConfigVersion,
        Integer minSamplesPerMetric,
        Long maxSamplingGapSeconds,
        MlModelFamily modelFamily,
        Integer horizonDays,
        MlMetricSelectionMode metricSelectionMode) {
}
