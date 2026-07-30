package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import java.time.Instant;
import java.util.UUID;

public record MlDatasetVersionDto(
        UUID id,
        UUID datasetId,
        UUID accountId,
        int version,
        MlDatasetSourceType sourceType,
        MlDatasetStatus status,
        MlTrainingSplitStrategy splitStrategy,
        String splitConfigJson,
        String featureConfigVersion,
        Instant sourceWindowStart,
        Instant sourceWindowEnd,
        Instant createdAt,
        Instant readyAt) {
}
