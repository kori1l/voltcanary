package com.voltcanary.analytics.ml;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MlInferenceResponse(
        UUID deviceId,
        String modelName,
        String modelVersion,
        String modelType,
        String modelStatus,
        String predictionStatus,
        double anomalyScore,
        double degradationScore,
        String riskLevel,
        double confidence,
        List<Map<String, Object>> signals,
        List<String> explanations,
        List<String> recommendedActions,
        String inputHash) {

    public MlInferenceResponse(
            UUID deviceId,
            String modelVersion,
            double anomalyScore,
            double degradationScore,
            String riskLevel,
            double confidence,
            List<String> explanations) {
        this(
                deviceId,
                modelVersion,
                modelVersion,
                "",
                "",
                "BASELINE_HEURISTIC",
                anomalyScore,
                degradationScore,
                riskLevel,
                confidence,
                List.of(),
                explanations,
                List.of(),
                "");
    }
}
