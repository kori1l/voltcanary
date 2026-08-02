package com.voltcanary.analytics.ml;

import java.util.Map;

public record MlTrainingResponse(
        String runId,
        String status,
        String datasetVersionId,
        String modelVersionId,
        String modelVersion,
        String artifactUri,
        String manifestUri,
        String artifactHash,
        Long artifactSizeBytes,
        Map<String, Object> metrics) {
}
