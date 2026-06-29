package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.List;

public record RuleEvaluationResponse(
        boolean matched,
        int matchCount,
        boolean wouldCreateAlert,
        String severity,
        String previewTitle,
        String previewDescription,
        String evaluationStatus,
        List<Evidence> evidence) {

    public record Evidence(
            Instant timestamp,
            String metric,
            Double value,
            Double threshold,
            String alertId) {
    }
}
