package com.voltcanary.analytics.ml;

import java.util.List;
import java.util.UUID;

public record MlTrainingRequest(
        UUID trainingRunId,
        String idempotencyKey,
        String artifactBundleId,
        UUID datasetVersionId,
        UUID accountId,
        String sourceType,
        String modelFamily,
        String algorithm,
        Integer failureHorizonDays,
        Integer minimumPositiveLabels,
        Double minimumLabelCoverage,
        List<MlTrainingMetricRow> rows) {
}
