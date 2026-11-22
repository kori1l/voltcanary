package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.domain.AlertRule;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.dto.CreateAlertRuleRequest;
import com.voltcanary.alert.dto.UpdateAlertRuleRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class AlertRuleDefinition {
    private static final TypeReference<List<UUID>> UUID_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {};
    private static final Set<String> RULE_TYPES =
            Set.of(
                    "POWER_SPIKE",
                    "CONSUMPTION_GROWTH",
                    "LONG_CYCLE",
                    "OFFLINE",
                    "THRESHOLD",
                    "CUSTOM");
    private static final Set<String> SCOPE_TYPES =
            Set.of("FLEET", "LOCATION", "DEVICE_TYPE", "DEVICE");
    private static final Set<String> CONDITION_TYPES =
            Set.of(
                    "ABOVE_THRESHOLD",
                    "BELOW_THRESHOLD",
                    "BASELINE_MULTIPLIER",
                    "GROWTH_RATE",
                    "MISSING_TELEMETRY",
                    "LONG_HIGH_RUN");

    private final ObjectMapper objectMapper;

    void applyCreate(AlertRule rule, CreateAlertRuleRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Alert rule request is required");
        }
        rule.setName(nonBlank(request.name(), "name", 160));
        rule.setDescription(optionalText(request.description(), 1000));
        rule.setRuleType(enumValue(request.ruleType(), "ruleType", RULE_TYPES));
        rule.setEnabled(request.enabled() == null || request.enabled());
        rule.setScopeType(
                enumValue(
                        request.scopeType() == null ? "FLEET" : request.scopeType(),
                        "scopeType",
                        SCOPE_TYPES));
        rule.setScopeId(request.scopeId());
        rule.setDeviceType(optionalText(request.deviceType(), 100));
        rule.setMetric(nonBlank(request.metric(), "metric", 120));
        rule.setConditionType(enumValue(request.conditionType(), "conditionType", CONDITION_TYPES));
        rule.setThreshold(request.threshold());
        rule.setThresholdUnit(optionalText(request.thresholdUnit(), 32));
        rule.setBaselineMultiplier(request.baselineMultiplier());
        rule.setMinPoints(request.minPoints());
        rule.setSeverity(enumValue(request.severity(), "severity", severityValues()));
        rule.setCooldownSeconds(
                normalizedPositive(request.cooldownSeconds(), 900, "cooldownSeconds"));
        rule.setDeduplicationWindowSeconds(
                normalizedPositive(
                        request.deduplicationWindowSeconds(), 900, "deduplicationWindowSeconds"));
        rule.setNotificationChannelIdsJson(
                writeValue(
                        request.notificationChannelIds() == null
                                ? List.of()
                                : request.notificationChannelIds()));
        rule.setConfigJson(writeValue(request.config() == null ? Map.of() : request.config()));
        validate(rule);
    }

    void applyUpdate(AlertRule rule, UpdateAlertRuleRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Alert rule request is required");
        }
        if (request.name() != null) {
            rule.setName(nonBlank(request.name(), "name", 160));
        }
        if (request.description() != null) {
            rule.setDescription(optionalText(request.description(), 1000));
        }
        if (request.ruleType() != null) {
            rule.setRuleType(enumValue(request.ruleType(), "ruleType", RULE_TYPES));
        }
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        if (request.scopeType() != null) {
            rule.setScopeType(enumValue(request.scopeType(), "scopeType", SCOPE_TYPES));
        }
        if (request.scopeId() != null || "FLEET".equals(rule.getScopeType())) {
            rule.setScopeId(request.scopeId());
        }
        if (request.deviceType() != null) {
            rule.setDeviceType(optionalText(request.deviceType(), 100));
        }
        if (request.metric() != null) {
            rule.setMetric(nonBlank(request.metric(), "metric", 120));
        }
        if (request.conditionType() != null) {
            rule.setConditionType(
                    enumValue(request.conditionType(), "conditionType", CONDITION_TYPES));
        }
        if (request.threshold() != null) {
            rule.setThreshold(request.threshold());
        }
        if (request.thresholdUnit() != null) {
            rule.setThresholdUnit(optionalText(request.thresholdUnit(), 32));
        }
        if (request.baselineMultiplier() != null) {
            rule.setBaselineMultiplier(request.baselineMultiplier());
        }
        if (request.minPoints() != null) {
            rule.setMinPoints(request.minPoints());
        }
        if (request.severity() != null) {
            rule.setSeverity(enumValue(request.severity(), "severity", severityValues()));
        }
        if (request.cooldownSeconds() != null) {
            rule.setCooldownSeconds(
                    normalizedPositive(request.cooldownSeconds(), 900, "cooldownSeconds"));
        }
        if (request.deduplicationWindowSeconds() != null) {
            rule.setDeduplicationWindowSeconds(
                    normalizedPositive(
                            request.deduplicationWindowSeconds(),
                            900,
                            "deduplicationWindowSeconds"));
        }
        if (request.notificationChannelIds() != null) {
            rule.setNotificationChannelIdsJson(writeValue(request.notificationChannelIds()));
        }
        if (request.config() != null) {
            rule.setConfigJson(writeValue(request.config()));
        }
        validate(rule);
    }

    void validate(AlertRule rule) {
        if (("DEVICE".equals(rule.getScopeType()) || "LOCATION".equals(rule.getScopeType()))
                && rule.getScopeId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    rule.getScopeType().toLowerCase(Locale.ROOT) + " scope requires scopeId");
        }
        if ("DEVICE_TYPE".equals(rule.getScopeType())
                && (rule.getDeviceType() == null || rule.getDeviceType().isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "device type scope requires deviceType");
        }
        if (Set.of("ABOVE_THRESHOLD", "BELOW_THRESHOLD").contains(rule.getConditionType())
                && rule.getThreshold() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "threshold is required for " + rule.getConditionType());
        }
        if ("BASELINE_MULTIPLIER".equals(rule.getConditionType())
                && rule.getBaselineMultiplier() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "baselineMultiplier is required for baseline multiplier rules");
        }
        if (Set.of("GROWTH_RATE", "LONG_HIGH_RUN").contains(rule.getConditionType())
                && rule.getMinPoints() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "minPoints is required for " + rule.getConditionType());
        }
    }

    String nonBlank(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        if (trimmed.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
        }
        return trimmed;
    }

    String optionalText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value is too long");
        }
        return trimmed;
    }

    String enumValue(String value, String field, Set<String> allowed) {
        String normalized = nonBlank(value, field, 100).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported " + field);
        }
        return normalized;
    }

    int normalizedPositive(Integer value, int defaultValue, String field) {
        int normalized = value == null ? defaultValue : value;
        if (normalized <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be positive");
        }
        return normalized;
    }

    Set<String> severityValues() {
        return Set.of(
                AlertSeverity.CRITICAL.name(),
                AlertSeverity.HIGH.name(),
                AlertSeverity.WARNING.name(),
                AlertSeverity.INFO.name());
    }

    String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid alert rule JSON field", exception);
        }
    }

    List<UUID> readNotificationChannelIds(String value) {
        try {
            return objectMapper.readValue(
                    value == null || value.isBlank() ? "[]" : value, UUID_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored notification channel ids are invalid", exception);
        }
    }

    Map<String, Object> readConfig(String value) {
        try {
            return objectMapper.readValue(
                    value == null || value.isBlank() ? "{}" : value, CONFIG_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored alert rule config is invalid", exception);
        }
    }

    String normalizeOptional(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value)
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }

    String normalizeMetric(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value)
                ? null
                : value.trim();
    }
}
