package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import java.time.Instant;
import java.util.UUID;

public record MlDatasetSummaryDto(
        UUID datasetId,
        UUID datasetVersionId,
        String name,
        String description,
        MlDatasetSourceType sourceType,
        int version,
        MlDatasetStatus status,
        MlTrainingSplitStrategy splitStrategy,
        String featureConfigVersion,
        Instant sourceWindowStart,
        Instant sourceWindowEnd,
        Instant createdAt,
        Instant readyAt,
        String failureReason) {
}
