package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlModelFamily;
import com.voltcanary.analytics.domain.MlModelStatus;
import java.time.Instant;
import java.util.UUID;

public record MlModelVersionDto(
        UUID id,
        UUID accountId,
        String modelKey,
        int version,
        MlModelFamily family,
        String algorithm,
        MlModelStatus status,
        UUID datasetVersionId,
        UUID featureSnapshotId,
        String featureSchemaHash,
        String artifactUri,
        String artifactManifestUri,
        String artifactHash,
        Long artifactSizeBytes,
        String compatibleDeviceTypesJson,
        String supportedMetricsJson,
        String evaluationMetricsJson,
        String modelOrigin,
        boolean demoModel,
        boolean systemHeuristic,
        UUID approvedBy,
        Instant approvedAt,
        Instant createdAt,
        UUID trainingRunId) {
}
