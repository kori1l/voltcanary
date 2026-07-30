package com.voltcanary.analytics.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;

public record MlFeatureSnapshotRequest(
        @NotNull UUID datasetVersionId,
        String featureSetVersion,
        String codeConfigVersion,
        @Positive Integer rollingWindowSize,
        @Positive Long missingGapSeconds,
        List<String> enabledFeatureFamilies) {
}
