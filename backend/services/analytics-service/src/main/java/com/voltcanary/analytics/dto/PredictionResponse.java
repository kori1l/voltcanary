package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PredictionResponse(
        UUID deviceId,
        String modelName,
        String modelVersion,
        String modelType,
        String modelStatus,
        String predictionStatus,
        double anomalyScore,
        double degradationScore,
        double confidence,
        String inputHash,
        List<Map<String, Object>> signals,
        List<String> explanations,
        List<String> recommendedActions,
        Instant createdAt) {
}
