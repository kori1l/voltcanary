package com.voltcanary.analytics.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AlertRuleSnapshot(
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
        int version) {
}
