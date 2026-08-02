package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlModelFamily;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MlTrainingRunRequest(
        @NotNull UUID datasetVersionId,
        @NotNull UUID featureSnapshotId,
        @NotNull MlModelFamily modelFamily,
        String algorithm,
        Integer failureHorizonDays,
        Integer minimumPositiveLabels,
        Double minimumLabelCoverage,
        String hyperparametersJson) {

    public MlTrainingRunRequest(
            UUID datasetVersionId,
            UUID featureSnapshotId,
            MlModelFamily modelFamily,
            String algorithm,
            String hyperparametersJson) {
        this(
                datasetVersionId,
                featureSnapshotId,
                modelFamily,
                algorithm,
                null,
                null,
                null,
                hyperparametersJson);
    }
}
