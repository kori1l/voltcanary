package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PredictionResponse(
        UUID deviceId,
        String modelVersion,
        double anomalyScore,
        double degradationScore,
        double confidence,
        List<String> explanations,
        Instant createdAt) {
}
