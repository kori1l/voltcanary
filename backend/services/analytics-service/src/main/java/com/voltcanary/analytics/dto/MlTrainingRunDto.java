package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import java.time.Instant;
import java.util.UUID;

public record MlTrainingRunDto(
        UUID id,
        UUID accountId,
        UUID datasetVersionId,
        UUID featureSnapshotId,
        MlModelFamily modelFamily,
        String algorithm,
        String hyperparametersJson,
        MlTrainingRunStatus status,
        String metricsJson,
        String artifactUri,
        String logsSummary,
        String failureReason,
        UUID createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {
}
