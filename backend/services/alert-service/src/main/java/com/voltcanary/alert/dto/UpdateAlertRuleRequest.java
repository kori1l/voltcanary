package com.voltcanary.alert.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateAlertRuleRequest(
        String name,
        String description,
        String ruleType,
        Boolean enabled,
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
        Integer cooldownSeconds,
        Integer deduplicationWindowSeconds,
        List<UUID> notificationChannelIds,
        Map<String, Object> config) {
}
