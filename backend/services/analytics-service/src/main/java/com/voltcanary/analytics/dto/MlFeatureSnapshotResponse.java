package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.UUID;

public record MlFeatureSnapshotResponse(
        UUID id,
        UUID accountId,
        UUID datasetVersionId,
        String featureSetVersion,
        String codeConfigVersion,
        String featureSchemaHash,
        long rowCount,
        long rejectedRowCount,
        String artifactUri,
        Instant createdAt) {
}
