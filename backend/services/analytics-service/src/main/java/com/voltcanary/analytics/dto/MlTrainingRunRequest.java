package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlModelFamily;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MlTrainingRunRequest(
        @NotNull UUID datasetVersionId,
        @NotNull UUID featureSnapshotId,
        @NotNull MlModelFamily modelFamily,
        String algorithm,
        String hyperparametersJson) {
}
