package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.domain.AlertRule;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.dto.AlertRuleResponse;
import com.voltcanary.alert.dto.AlertRuleSnapshotResponse;
import com.voltcanary.alert.dto.AlertRuleTestRequest;
import com.voltcanary.alert.dto.AlertRuleTestResponse;
import com.voltcanary.alert.dto.CreateAlertRuleRequest;
import com.voltcanary.alert.dto.UpdateAlertRuleRequest;
import com.voltcanary.alert.repository.AlertRuleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertRuleService {

    private static final TypeReference<List<UUID>> UUID_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {
    };
    private static final Set<String> RULE_TYPES = Set.of(
            "POWER_SPIKE",
            "CONSUMPTION_GROWTH",
            "LONG_CYCLE",
            "OFFLINE",
            "THRESHOLD",
            "CUSTOM");
    private static final Set<String> SCOPE_TYPES = Set.of("FLEET", "LOCATION", "DEVICE_TYPE", "DEVICE");
    private static final Set<String> CONDITION_TYPES = Set.of(
            "ABOVE_THRESHOLD",
            "BELOW_THRESHOLD",
            "BASELINE_MULTIPLIER",
            "GROWTH_RATE",
            "MISSING_TELEMETRY",
            "LONG_HIGH_RUN");

    private final AlertRuleRepository repository;
    private final ObjectMapper objectMapper;
    private final AlertAccessService accessService;
    private final AlertRuleEvaluationClient evaluationClient;

    public AlertRuleService(
            AlertRuleRepository repository,
            ObjectMapper objectMapper,
            AlertAccessService accessService,
            AlertRuleEvaluationClient evaluationClient) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.accessService = accessService;
        this.evaluationClient = evaluationClient;
    }

    @Transactional(readOnly = true)
    public List<AlertRuleResponse> list(
            String userId,
            String accountId,
            Boolean enabled,
            String ruleType,
            String scopeType,
            String metric) {
        UUID scope = accountScope(userId, accountId);
        Specification<AlertRule> spec = Specification.allOf(
                equalsIfPresent("accountId", scope),
                equalsIfPresent("enabled", enabled),
                equalsIfPresent("ruleType", normalizeOptional(ruleType)),
                equalsIfPresent("scopeType", normalizeOptional(scopeType)),
                equalsIfPresent("metric", normalizeMetric(metric)));
        return repository.findAll(spec, Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AlertRuleResponse get(UUID id, String userId, String accountId) {
        return toResponse(loadOwned(id, userId, accountId));
    }

    @Transactional
    public AlertRuleResponse create(String userId, String accountId, CreateAlertRuleRequest request) {
        UUID scope = accountScope(userId, accountId);
        Instant now = Instant.now();
        AlertRule rule = new AlertRule();
        rule.setId(UUID.randomUUID());
        rule.setAccountId(scope);
        rule.setVersion(1);
        rule.setCreatedBy(parseUuid(userId));
        rule.setUpdatedBy(parseUuid(userId));
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        applyCreate(rule, request);
        return toResponse(repository.save(rule));
    }

    @Transactional
    public AlertRuleResponse update(UUID id, String userId, String accountId, UpdateAlertRuleRequest request) {
        AlertRule rule = loadOwned(id, userId, accountId);
        applyUpdate(rule, request);
        rule.setVersion(rule.getVersion() + 1);
        rule.setUpdatedBy(parseUuid(userId));
        rule.setUpdatedAt(Instant.now());
        return toResponse(rule);
    }

    @Transactional
    public AlertRuleResponse setEnabled(UUID id, String userId, String accountId, boolean enabled) {
        AlertRule rule = loadOwned(id, userId, accountId);
        rule.setEnabled(enabled);
        rule.setVersion(rule.getVersion() + 1);
        rule.setUpdatedBy(parseUuid(userId));
        rule.setUpdatedAt(Instant.now());
        return toResponse(rule);
    }

    @Transactional
    public void delete(UUID id, String userId, String accountId) {
        repository.delete(loadOwned(id, userId, accountId));
    }

    @Transactional(readOnly = true)
    public AlertRuleTestResponse test(String userId, String accountId, String correlationId, AlertRuleTestRequest request) {
        accountScope(userId, accountId);
        if (request == null || request.rule() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rule test request is required");
        }
        AlertRule draft = new AlertRule();
        draft.setId(UUID.randomUUID());
        draft.setAccountId(accountScope(userId, accountId));
        draft.setVersion(1);
        draft.setCreatedAt(Instant.now());
        draft.setUpdatedAt(Instant.now());
        applyCreate(draft, request.rule());
        if (request.deviceId() != null) {
            return evaluationClient.test(request.deviceId(), request.rule(), request.from(), request.to(), userId, correlationId);
        }
        String title = title(draft.getRuleType());
        return new AlertRuleTestResponse(
                false,
                0,
                false,
                draft.getSeverity(),
                title,
                "Rule is valid. Telemetry-backed rule preview will be evaluated by analytics integration in the next backend step.",
                "VALID_NOT_EVALUATED",
                List.of());
    }

    @Transactional(readOnly = true)
    public List<AlertRuleResponse> effectiveForDevice(String userId, String accountId, UUID deviceId, String correlationId) {
        UUID scope = accountScope(userId, accountId);
        if (deviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        AlertAccessService.DeviceRuleContext device = accessService.resolveDeviceRuleContext(deviceId, correlationId);
        if (device != null && device.accountId() != null && !scope.equals(device.accountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        Specification<AlertRule> spec = Specification.allOf(
                equalsIfPresent("accountId", scope),
                equalsIfPresent("enabled", true));
        return repository.findAll(spec, Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .filter(rule -> appliesToDevice(rule, deviceId, device))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AlertRuleSnapshotResponse> effectiveRuleSnapshotsForDevice(UUID deviceId, String correlationId) {
        if (deviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        AlertAccessService.DeviceRuleContext device = accessService.resolveDeviceRuleContext(deviceId, correlationId);
        if (device == null || device.accountId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        Specification<AlertRule> spec = Specification.allOf(
                equalsIfPresent("accountId", device.accountId()),
                equalsIfPresent("enabled", true));
        return repository.findAll(spec, Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
                .filter(rule -> appliesToDevice(rule, deviceId, device))
                .map(this::toSnapshot)
                .toList();
    }

    private boolean appliesToDevice(AlertRule rule, UUID deviceId, AlertAccessService.DeviceRuleContext device) {
        return switch (rule.getScopeType()) {
            case "FLEET" -> true;
            case "DEVICE" -> deviceId.equals(rule.getScopeId());
            case "LOCATION" -> device != null && device.locationId() != null && device.locationId().equals(rule.getScopeId());
            case "DEVICE_TYPE" -> device != null && device.type() != null && device.type().equalsIgnoreCase(rule.getDeviceType());
            default -> false;
        };
    }

    private AlertRule loadOwned(UUID id, String userId, String accountId) {
        return repository.findByIdAndAccountId(id, accountScope(userId, accountId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert rule not found"));
    }

    private void applyCreate(AlertRule rule, CreateAlertRuleRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alert rule request is required");
        }
        rule.setName(nonBlank(request.name(), "name", 160));
        rule.setDescription(optionalText(request.description(), 1000));
        rule.setRuleType(enumValue(request.ruleType(), "ruleType", RULE_TYPES));
        rule.setEnabled(request.enabled() == null || request.enabled());
        rule.setScopeType(enumValue(request.scopeType() == null ? "FLEET" : request.scopeType(), "scopeType", SCOPE_TYPES));
        rule.setScopeId(request.scopeId());
        rule.setDeviceType(optionalText(request.deviceType(), 100));
        rule.setMetric(nonBlank(request.metric(), "metric", 120));
        rule.setConditionType(enumValue(request.conditionType(), "conditionType", CONDITION_TYPES));
        rule.setThreshold(request.threshold());
        rule.setThresholdUnit(optionalText(request.thresholdUnit(), 32));
        rule.setBaselineMultiplier(request.baselineMultiplier());
        rule.setMinPoints(request.minPoints());
        rule.setSeverity(enumValue(request.severity(), "severity", severityValues()));
        rule.setCooldownSeconds(normalizedPositive(request.cooldownSeconds(), 900, "cooldownSeconds"));
        rule.setDeduplicationWindowSeconds(normalizedPositive(request.deduplicationWindowSeconds(), 900, "deduplicationWindowSeconds"));
        rule.setNotificationChannelIdsJson(writeValue(request.notificationChannelIds() == null ? List.of() : request.notificationChannelIds()));
        rule.setConfigJson(writeValue(request.config() == null ? Map.of() : request.config()));
        validate(rule);
    }

    private void applyUpdate(AlertRule rule, UpdateAlertRuleRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alert rule request is required");
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
            rule.setConditionType(enumValue(request.conditionType(), "conditionType", CONDITION_TYPES));
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
            rule.setCooldownSeconds(normalizedPositive(request.cooldownSeconds(), 900, "cooldownSeconds"));
        }
        if (request.deduplicationWindowSeconds() != null) {
            rule.setDeduplicationWindowSeconds(normalizedPositive(request.deduplicationWindowSeconds(), 900, "deduplicationWindowSeconds"));
        }
        if (request.notificationChannelIds() != null) {
            rule.setNotificationChannelIdsJson(writeValue(request.notificationChannelIds()));
        }
        if (request.config() != null) {
            rule.setConfigJson(writeValue(request.config()));
        }
        validate(rule);
    }

    private void validate(AlertRule rule) {
        if (("DEVICE".equals(rule.getScopeType()) || "LOCATION".equals(rule.getScopeType())) && rule.getScopeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rule.getScopeType().toLowerCase(Locale.ROOT) + " scope requires scopeId");
        }
        if ("DEVICE_TYPE".equals(rule.getScopeType()) && (rule.getDeviceType() == null || rule.getDeviceType().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device type scope requires deviceType");
        }
        if (Set.of("ABOVE_THRESHOLD", "BELOW_THRESHOLD").contains(rule.getConditionType()) && rule.getThreshold() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threshold is required for " + rule.getConditionType());
        }
        if ("BASELINE_MULTIPLIER".equals(rule.getConditionType()) && rule.getBaselineMultiplier() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baselineMultiplier is required for baseline multiplier rules");
        }
        if (Set.of("GROWTH_RATE", "LONG_HIGH_RUN").contains(rule.getConditionType()) && rule.getMinPoints() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minPoints is required for " + rule.getConditionType());
        }
    }

    private UUID accountScope(String userId, String accountId) {
        String scope = accountId == null || accountId.isBlank() ? userId : accountId;
        if (scope == null || scope.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User context is required");
        }
        try {
            return UUID.fromString(scope);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account scope must be a UUID");
        }
    }

    private String nonBlank(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        if (trimmed.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
        }
        return trimmed;
    }

    private String optionalText(String value, int maxLength) {
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

    private String enumValue(String value, String field, Set<String> allowed) {
        String normalized = nonBlank(value, field, 100).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported " + field);
        }
        return normalized;
    }

    private int normalizedPositive(Integer value, int defaultValue, String field) {
        int normalized = value == null ? defaultValue : value;
        if (normalized <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be positive");
        }
        return normalized;
    }

    private Set<String> severityValues() {
        return Set.of(AlertSeverity.CRITICAL.name(), AlertSeverity.HIGH.name(), AlertSeverity.WARNING.name(), AlertSeverity.INFO.name());
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid alert rule JSON field", exception);
        }
    }

    private List<UUID> readNotificationChannelIds(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "[]" : value, UUID_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored notification channel ids are invalid", exception);
        }
    }

    private Map<String, Object> readConfig(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, CONFIG_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored alert rule config is invalid", exception);
        }
    }

    private AlertRuleResponse toResponse(AlertRule rule) {
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
                readNotificationChannelIds(rule.getNotificationChannelIdsJson()),
                readConfig(rule.getConfigJson()),
                rule.getVersion(),
                rule.getCreatedAt(),
                rule.getUpdatedAt());
    }

    private AlertRuleSnapshotResponse toSnapshot(AlertRule rule) {
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
                rule.getVersion());
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeMetric(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value) ? null : value.trim();
    }

    private <T> Specification<AlertRule> equalsIfPresent(String field, T value) {
        return value == null ? null : (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(field), value);
    }

    private String title(String ruleType) {
        return switch (ruleType) {
            case "POWER_SPIKE" -> "Power spike detected";
            case "CONSUMPTION_GROWTH" -> "Consumption growth detected";
            case "LONG_CYCLE" -> "Long cycle detected";
            case "OFFLINE" -> "Telemetry stopped";
            default -> "Rule matched";
        };
    }
}
