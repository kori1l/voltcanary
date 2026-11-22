package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.AlertRule;
import com.voltcanary.alert.dto.AlertRuleResponse;
import com.voltcanary.alert.dto.AlertRuleSnapshotResponse;
import com.voltcanary.alert.dto.AlertRuleTestRequest;
import com.voltcanary.alert.dto.AlertRuleTestResponse;
import com.voltcanary.alert.dto.CreateAlertRuleRequest;
import com.voltcanary.alert.dto.UpdateAlertRuleRequest;
import com.voltcanary.alert.repository.AlertRuleRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private final AlertRuleRepository repository;
    private final AlertRuleWrites writes;
    private final AlertRuleDefinition definition;
    private final AlertRuleViews views;
    private final AlertAccessService accessService;
    private final AlertRuleEvaluationClient evaluationClient;
    private final VariableRuleValidation variableRules;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<AlertRuleResponse> list(
        String userId,
        String accountId,
        Boolean enabled,
        String ruleType,
        String scopeType,
        String metric
    ) {
        return list(userId, accountId, enabled, ruleType, scopeType, metric, 0, 100);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<AlertRuleResponse> list(
        String userId,
        String accountId,
        Boolean enabled,
        String ruleType,
        String scopeType,
        String metric,
        int page,
        int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "page must be non-negative and size must be between 1 and 100"
            );
        }
        UUID scope = accountScope(userId, accountId);
        Specification<AlertRule> spec = Specification.allOf(
            equalsIfPresent("accountId", scope),
            equalsIfPresent("enabled", enabled),
            equalsIfPresent("ruleType", definition.normalizeOptional(ruleType)),
            equalsIfPresent("scopeType", definition.normalizeOptional(scopeType)),
            equalsIfPresent("metric", definition.normalizeMetric(metric))
        );
        var found = repository.findAll(
            spec,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt", "id"))
        );
        return found.stream().map(views::toResponse).toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AlertRuleResponse get(UUID id, String userId, String accountId) {
        return views.toResponse(loadOwned(id, userId, accountId));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AlertRuleResponse create(
        String userId,
        String accountId,
        CreateAlertRuleRequest request
    ) {
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
        definition.applyCreate(rule, request);
        variableRules.validate(rule, userId, null);
        return views.toResponse(writes.create(rule));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AlertRuleResponse update(
        UUID id,
        String userId,
        String accountId,
        UpdateAlertRuleRequest request
    ) {
        AlertRule rule = draft(loadOwned(id, userId, accountId));
        int expectedVersion = rule.getVersion();
        if (variableRules.applies(rule.getMetric(), variableRules.config(rule))) {
            Object expected =
                request == null || request.config() == null
                    ? null
                    : request.config().get("expectedVersion");
            if (!(expected instanceof Number number) || number.intValue() != rule.getVersion()) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Variable rule changed; reload"
                );
            }
        }
        definition.applyUpdate(rule, request);
        variableRules.validate(
            rule,
            userId,
            variableRules.applies(rule.getMetric(), variableRules.config(rule))
                ? rule.getVersion()
                : null
        );
        rule.setVersion(rule.getVersion() + 1);
        rule.setUpdatedBy(parseUuid(userId));
        rule.setUpdatedAt(Instant.now());
        return views.toResponse(writes.update(rule, expectedVersion));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AlertRuleResponse setEnabled(UUID id, String userId, String accountId, boolean enabled) {
        AlertRule rule = draft(loadOwned(id, userId, accountId));
        int expectedVersion = rule.getVersion();
        rule.setEnabled(enabled);
        variableRules.validate(rule, userId, null);
        rule.setVersion(rule.getVersion() + 1);
        rule.setUpdatedBy(parseUuid(userId));
        rule.setUpdatedAt(Instant.now());
        return views.toResponse(writes.update(rule, expectedVersion));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void delete(UUID id, String userId, String accountId) {
        writes.delete(loadOwned(id, userId, accountId));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AlertRuleTestResponse test(
        String userId,
        String accountId,
        String correlationId,
        AlertRuleTestRequest request
    ) {
        accountScope(userId, accountId);
        if (request == null || request.rule() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Rule test request is required"
            );
        }
        AlertRule draft = new AlertRule();
        draft.setId(UUID.randomUUID());
        draft.setAccountId(accountScope(userId, accountId));
        draft.setVersion(1);
        draft.setCreatedAt(Instant.now());
        draft.setUpdatedAt(Instant.now());
        definition.applyCreate(draft, request.rule());
        variableRules.validate(draft, userId, null);
        if (request.deviceId() != null) {
            return evaluationClient.test(
                request.deviceId(),
                request.rule(),
                request.from(),
                request.to(),
                userId,
                correlationId
            );
        }
        String title = views.title(draft.getRuleType());
        return new AlertRuleTestResponse(
            false,
            0,
            false,
            draft.getSeverity(),
            title,
            "Rule is valid. Telemetry-backed rule preview will be evaluated by analytics" +
                " integration in the next backend step.",
            "VALID_NOT_EVALUATED",
            List.of()
        );
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<AlertRuleResponse> effectiveForDevice(
        String userId,
        String accountId,
        UUID deviceId,
        String correlationId
    ) {
        UUID scope = accountScope(userId, accountId);
        if (deviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        AlertAccessService.DeviceRuleContext device = accessService.resolveDeviceRuleContext(
            deviceId,
            correlationId
        );
        if (device != null && device.accountId() != null && !scope.equals(device.accountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        Specification<AlertRule> spec = Specification.allOf(
            equalsIfPresent("accountId", scope),
            equalsIfPresent("enabled", true)
        );
        return repository
            .findAll(spec, Sort.by(Sort.Direction.DESC, "updatedAt"))
            .stream()
            .filter(rule -> appliesToDevice(rule, deviceId, device))
            .map(views::toResponse)
            .toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<AlertRuleSnapshotResponse> effectiveRuleSnapshotsForDevice(
        UUID deviceId,
        String correlationId
    ) {
        if (deviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        AlertAccessService.DeviceRuleContext device = accessService.resolveDeviceRuleContext(
            deviceId,
            correlationId
        );
        if (device == null || device.accountId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        Specification<AlertRule> spec = Specification.allOf(
            equalsIfPresent("accountId", device.accountId()),
            equalsIfPresent("enabled", true)
        );
        return repository
            .findAll(spec, Sort.by(Sort.Direction.DESC, "updatedAt"))
            .stream()
            .filter(rule -> appliesToDevice(rule, deviceId, device))
            .map(views::toSnapshot)
            .toList();
    }

    private boolean appliesToDevice(
        AlertRule rule,
        UUID deviceId,
        AlertAccessService.DeviceRuleContext device
    ) {
        return switch (rule.getScopeType()) {
            case "FLEET" -> true;
            case "DEVICE" -> deviceId.equals(rule.getScopeId());
            case "LOCATION" -> device != null &&
                device.locationId() != null &&
                device.locationId().equals(rule.getScopeId());
            case "DEVICE_TYPE" -> device != null &&
                device.type() != null &&
                device.type().equalsIgnoreCase(rule.getDeviceType());
            default -> false;
        };
    }

    private AlertRule loadOwned(UUID id, String userId, String accountId) {
        AlertRule rule = repository
            .findByIdAndAccountId(id, accountScope(userId, accountId))
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert rule not found")
            );
        if (
            variableRules.applies(rule.getMetric(), variableRules.config(rule)) &&
            !accessService.ownAccount(userId).equals(rule.getAccountId())
        ) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert rule not found");
        }
        return rule;
    }

    private AlertRule draft(AlertRule source) {
        var copy = new AlertRule();
        copy.setId(source.getId());
        copy.setAccountId(source.getAccountId());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setRuleType(source.getRuleType());
        copy.setEnabled(source.isEnabled());
        copy.setScopeType(source.getScopeType());
        copy.setScopeId(source.getScopeId());
        copy.setDeviceType(source.getDeviceType());
        copy.setMetric(source.getMetric());
        copy.setConditionType(source.getConditionType());
        copy.setThreshold(source.getThreshold());
        copy.setThresholdUnit(source.getThresholdUnit());
        copy.setBaselineMultiplier(source.getBaselineMultiplier());
        copy.setMinPoints(source.getMinPoints());
        copy.setSeverity(source.getSeverity());
        copy.setCooldownSeconds(source.getCooldownSeconds());
        copy.setDeduplicationWindowSeconds(source.getDeduplicationWindowSeconds());
        copy.setNotificationChannelIdsJson(source.getNotificationChannelIdsJson());
        copy.setConfigJson(source.getConfigJson());
        copy.setVersion(source.getVersion());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setUpdatedBy(source.getUpdatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private UUID accountScope(String userId, String accountId) {
        return accessService.ownAccount(userId);
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

    private <T> Specification<AlertRule> equalsIfPresent(String field, T value) {
        return value == null
            ? Specification.unrestricted()
            : (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(field), value);
    }
}
