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
    Instant to,
    java.util.Map<String, Object> config
) {
    public RuleEvaluationRequest(
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
        Instant to
    ) {
        this(
            deviceId,
            ruleType,
            metric,
            conditionType,
            threshold,
            thresholdUnit,
            baselineMultiplier,
            minPoints,
            severity,
            from,
            to,
            java.util.Map.of()
        );
    }
}
