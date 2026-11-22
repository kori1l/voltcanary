package com.voltcanary.alert.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AlertRuleSnapshotResponse(
    UUID id,
    String ruleType,
    boolean enabled,
    String metric,
    String conditionType,
    BigDecimal threshold,
    BigDecimal baselineMultiplier,
    Integer minPoints,
    String severity,
    Integer cooldownSeconds,
    Integer deduplicationWindowSeconds,
    int version,
    String thresholdUnit,
    java.util.Map<String, Object> config
) {
    public AlertRuleSnapshotResponse(
        UUID id,
        String ruleType,
        boolean enabled,
        String metric,
        String conditionType,
        BigDecimal threshold,
        BigDecimal baselineMultiplier,
        Integer minPoints,
        String severity,
        Integer cooldownSeconds,
        Integer deduplicationWindowSeconds,
        int version
    ) {
        this(
            id,
            ruleType,
            enabled,
            metric,
            conditionType,
            threshold,
            baselineMultiplier,
            minPoints,
            severity,
            cooldownSeconds,
            deduplicationWindowSeconds,
            version,
            null,
            java.util.Map.of()
        );
    }
}
