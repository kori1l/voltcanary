package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PredictionResponse(
        UUID deviceId,
        String modelVersionId,
        String modelFamily,
        String modelName,
        String modelVersion,
        String modelType,
        String modelStatus,
        String predictionStatus,
        double anomalyScore,
        double degradationScore,
        Double failureRiskScore,
        Integer failureHorizonDays,
        String failureRiskBand,
        double confidence,
        String inputHash,
        List<Map<String, Object>> riskDrivers,
        Double labelCoverage,
        Map<String, Object> trainingDataWindow,
        List<Map<String, Object>> signals,
        List<String> explanations,
        List<String> recommendedActions,
        Instant createdAt) {
}
