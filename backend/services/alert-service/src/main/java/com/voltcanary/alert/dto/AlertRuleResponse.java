package com.voltcanary.alert.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AlertRuleResponse(
        UUID id,
        UUID accountId,
        String name,
        String description,
        String ruleType,
        boolean enabled,
        String scopeType,
        UUID scopeId,
        String deviceType,
        String metric,
        String conditionType,
        BigDecimal threshold,
        String thresholdUnit,
        BigDecimal baselineMultiplier,
        Integer minPoints,
        String severity,
        int cooldownSeconds,
        int deduplicationWindowSeconds,
        List<UUID> notificationChannelIds,
        Map<String, Object> config,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
