package com.voltcanary.analytics.ml;

import java.util.List;
import java.util.Map;
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
        Map<String, Object> approvalGateProfile,
        List<MlTrainingMetricRow> rows) {
}
