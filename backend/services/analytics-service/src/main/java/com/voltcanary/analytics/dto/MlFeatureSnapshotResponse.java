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
        String featureDataHash,
        String featureConfigHash,
        long rowCount,
        long rejectedRowCount,
        String artifactUri,
        String immutableRowReference,
        String modelFamily,
        Integer horizonDays,
        String featureTemplateId,
        String featureTemplateVersion,
        Instant createdAt) {
}
