package com.voltcanary.analytics.ml;

import java.util.List;
import java.util.UUID;

public record MlInferenceResponse(
        UUID deviceId,
        String modelVersion,
        double anomalyScore,
        double degradationScore,
        String riskLevel,
        double confidence,
        List<String> explanations) {
}
