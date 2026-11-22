package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.AlertRule;
import com.voltcanary.alert.dto.AlertRuleResponse;
import com.voltcanary.alert.dto.AlertRuleSnapshotResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class AlertRuleViews {
    private final AlertRuleDefinition definition;

    AlertRuleResponse toResponse(AlertRule rule) {
        return new AlertRuleResponse(
                rule.getId(),
                rule.getAccountId(),
                rule.getName(),
                rule.getDescription(),
                rule.getRuleType(),
                rule.isEnabled(),
                rule.getScopeType(),
                rule.getScopeId(),
                rule.getDeviceType(),
                rule.getMetric(),
                rule.getConditionType(),
                rule.getThreshold(),
                rule.getThresholdUnit(),
                rule.getBaselineMultiplier(),
                rule.getMinPoints(),
                rule.getSeverity(),
                rule.getCooldownSeconds(),
                rule.getDeduplicationWindowSeconds(),
                definition.readNotificationChannelIds(rule.getNotificationChannelIdsJson()),
                definition.readConfig(rule.getConfigJson()),
                rule.getVersion(),
                rule.getCreatedAt(),
                rule.getUpdatedAt());
    }

    AlertRuleSnapshotResponse toSnapshot(AlertRule rule) {
        return new AlertRuleSnapshotResponse(
                rule.getId(),
                rule.getRuleType(),
                rule.isEnabled(),
                rule.getMetric(),
                rule.getConditionType(),
                rule.getThreshold(),
                rule.getBaselineMultiplier(),
                rule.getMinPoints(),
                rule.getSeverity(),
                rule.getCooldownSeconds(),
                rule.getDeduplicationWindowSeconds(),
                rule.getVersion(),
                rule.getThresholdUnit(),
                definition.readConfig(rule.getConfigJson()));
    }

    String title(String ruleType) {
        return switch (ruleType) {
            case "POWER_SPIKE" -> "Power spike detected";
            case "CONSUMPTION_GROWTH" -> "Consumption growth detected";
            case "LONG_CYCLE" -> "Long cycle detected";
            case "OFFLINE" -> "Telemetry stopped";
            default -> "Rule matched";
        };
    }
}
