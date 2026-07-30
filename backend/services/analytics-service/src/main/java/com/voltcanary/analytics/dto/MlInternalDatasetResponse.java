package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MlInternalDatasetResponse(
        UUID datasetId,
        UUID datasetVersionId,
        UUID accountId,
        int version,
        MlDatasetStatus status,
        MlTrainingSplitStrategy splitStrategy,
        long acceptedRows,
        long rejectedRows,
        long warningCount,
        List<String> validationMessages,
        Instant createdAt,
        Instant readyAt) {
}
