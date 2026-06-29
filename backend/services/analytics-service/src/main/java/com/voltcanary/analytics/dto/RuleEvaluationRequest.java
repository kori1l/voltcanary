package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.UUID;

public record RuleEvaluationRequest(
        UUID deviceId,
        String ruleType,
        String metric,
        String conditionType,
        Double threshold,
        String thresholdUnit,
        Double baselineMultiplier,
        Integer minPoints,
        String severity,
        Instant from,
        Instant to) {
}
