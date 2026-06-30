package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.deduplication.AlertDeduplicationService;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertEvent;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.dto.AlertListFilters;
import com.voltcanary.alert.dto.AlertPageResponse;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.dto.AlertSummaryResponse;
import com.voltcanary.alert.repository.AlertEventRepository;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alertRepository;
    private final AlertEventRepository alertEventRepository;
    private final AlertAccessService alertAccessService;
    private final AlertDeduplicationService deduplicationService;
    private final NotificationDeliveryService notificationDeliveryService;
    private final AlertMaintenanceSuppressionService maintenanceSuppressionService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public AlertService(
            AlertRepository alertRepository,
            AlertEventRepository alertEventRepository,
            AlertAccessService alertAccessService,
            AlertDeduplicationService deduplicationService,
            NotificationDeliveryService notificationDeliveryService,
            AlertMaintenanceSuppressionService maintenanceSuppressionService,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper) {
        this.alertRepository = alertRepository;
        this.alertEventRepository = alertEventRepository;
        this.alertAccessService = alertAccessService;
        this.deduplicationService = deduplicationService;
        this.notificationDeliveryService = notificationDeliveryService;
        this.maintenanceSuppressionService = maintenanceSuppressionService;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Alert handleAnomaly(AnomalyDetectedEvent event) {
        AlertSeverity severity = anomalySeverity(event.severity());
        String type = valueOrDefault(event.type(), "ANOMALY");
        String deduplicationKey = deduplicationService.key(event.deviceId(), type, severity, event.detectedAt());
        var existingBySourceEvent = deduplicationService.findBySourceEventId(event.eventId());
        if (existingBySourceEvent.isPresent()) {
            recordDeduplicationHit(existingBySourceEvent.get(), "source_event_id");
            return existingBySourceEvent.get();
        }
        if (maintenanceSuppressionService.suppressesEvent(
                event.deviceId(),
                event.eventId(),
                "ANOMALY_DETECTED",
                event.windowStart(),
                event.windowEnd(),
                event.detectedAt())) {
            return null;
        }
        var activeInWindow = deduplicationService.findActiveInWindow(event.deviceId(), type, severity, event.detectedAt());
        if (activeInWindow.isPresent()) {
            recordDeduplicationHit(activeInWindow.get(), "active_window");
            return activeInWindow.get();
        }
        String createDeduplicationKey = deduplicationService.findByDeduplicationKey(deduplicationKey)
                .filter(existing -> existing.getStatus() == AlertStatus.RESOLVED)
                .map(existing -> deduplicationService.reopenedKey(deduplicationKey, event.eventId()))
                .orElse(deduplicationKey);
        return findOrCreateAlert(
                createDeduplicationKey,
                event.eventId(),
                () -> createAlert(
                        event.deviceId(),
                        event.eventId(),
                        event.anomalyId(),
                        null,
                        event.ruleId(),
                        event.windowStart(),
                        event.windowEnd(),
                        event.inputHash(),
                        createDeduplicationKey,
                        type,
                        title(type),
                        event.description(),
                        severity,
                        "ANOMALY_DETECTED",
                        event,
                        event.detectedAt(),
                        "anomaly:" + event.eventId()));
    }

    @Transactional
    public Alert handleRiskScoreChanged(RiskScoreChangedEvent event) {
        AlertSeverity severity = riskSeverity(event.riskLevel());
        if (severity == AlertSeverity.INFO) {
            return null;
        }
        String type = "RISK_" + valueOrDefault(event.riskLevel(), "UNKNOWN");
        String deduplicationKey = deduplicationService.key(event.deviceId(), type, severity, event.changedAt());
        var existingBySourceEvent = deduplicationService.findBySourceEventId(event.eventId());
        if (existingBySourceEvent.isPresent()) {
            recordDeduplicationHit(existingBySourceEvent.get(), "source_event_id");
            return existingBySourceEvent.get();
        }
        if (maintenanceSuppressionService.suppressesEvent(
                event.deviceId(),
                event.eventId(),
                "RISK_SCORE_CHANGED",
                event.windowStart(),
                event.windowEnd(),
                event.changedAt())) {
            return null;
        }
        var activeInWindow = deduplicationService.findActiveInWindow(event.deviceId(), type, severity, event.changedAt());
        if (activeInWindow.isPresent()) {
            recordDeduplicationHit(activeInWindow.get(), "active_window");
            return activeInWindow.get();
        }
        String createDeduplicationKey = deduplicationService.findByDeduplicationKey(deduplicationKey)
                .filter(existing -> existing.getStatus() == AlertStatus.RESOLVED)
                .map(existing -> deduplicationService.reopenedKey(deduplicationKey, event.eventId()))
                .orElse(deduplicationKey);
        return findOrCreateAlert(
                createDeduplicationKey,
                event.eventId(),
                () -> createAlert(
                        event.deviceId(),
                        event.eventId(),
                        null,
                        event.riskScoreId(),
                        null,
                        event.windowStart(),
                        event.windowEnd(),
                        event.inputHash(),
                        createDeduplicationKey,
                        type,
                        "Risk score changed to " + event.riskLevel(),
                        "Risk score is " + event.currentScore() + " (" + event.riskLevel() + ").",
                        severity,
                        "RISK_SCORE_CHANGED",
                        event,
                        event.changedAt(),
                        "risk-score:" + event.eventId()));
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> listAlerts(String userId, String role, String correlationId, AlertListFilters filters) {
        AlertListFilters normalizedFilters = filters == null
                ? new AlertListFilters(null, null, null, null, null, null, null, null)
                : filters;
        return accessibleAlerts(userId, role, correlationId, normalizedFilters, true)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AlertSummaryResponse alertSummary(String userId, String role, String correlationId, AlertListFilters filters) {
        AlertListFilters normalizedFilters = filters == null
                ? new AlertListFilters(null, null, null, null, null, null, null, null)
                : filters;
        return toSummary(accessibleAlerts(userId, role, correlationId, normalizedFilters, false));
    }

    @Transactional(readOnly = true)
    public AlertPageResponse pageAlerts(
            String userId,
            String role,
            String correlationId,
            AlertListFilters filters,
            Integer page,
            Integer size) {
        AlertListFilters normalizedFilters = filters == null
                ? new AlertListFilters(null, null, null, null, null, null, null, null)
                : filters;
        int normalizedPage = normalizedPage(page);
        int normalizedSize = normalizedSize(size);
        List<Alert> alerts = accessibleAlerts(userId, role, correlationId, normalizedFilters, false);
        int fromIndex = Math.min(normalizedPage * normalizedSize, alerts.size());
        int toIndex = Math.min(fromIndex + normalizedSize, alerts.size());
        List<AlertResponse> content = alerts.subList(fromIndex, toIndex).stream()
                .map(this::toResponse)
                .toList();
        int totalPages = alerts.isEmpty() ? 0 : (int) Math.ceil((double) alerts.size() / normalizedSize);
        return new AlertPageResponse(
                content,
                normalizedPage,
                normalizedSize,
                alerts.size(),
                totalPages,
                normalizedPage + 1 < totalPages);
    }

    @Transactional(readOnly = true)
    public AlertPageResponse supportSearchAlerts(
            String userId,
            String role,
            AlertListFilters filters,
            UUID accountId,
            Integer page,
            Integer size) {
        alertAccessService.requirePrivileged(userId, role);
        AlertListFilters normalizedFilters = filters == null
                ? new AlertListFilters(null, null, null, null, null, null, null, null)
                : filters;
        int normalizedPage = normalizedPage(page);
        int normalizedSize = normalizedSize(size);
        Specification<Alert> spec = Specification.allOf(
                specification(normalizedFilters),
                equalsIfPresent("accountId", accountId));
        org.springframework.data.domain.Page<Alert> result = alertRepository.findAll(
                spec,
                PageRequest.of(normalizedPage, normalizedSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new AlertPageResponse(
                result.getContent().stream().map(this::toResponse).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    private List<Alert> accessibleAlerts(
            String userId,
            String role,
            String correlationId,
            AlertListFilters filters,
            boolean applyLimit) {
        alertAccessService.requireAuthenticated(userId);
        List<Alert> alerts = applyLimit
                ? alertRepository.findAll(
                        specification(filters),
                        PageRequest.of(
                                0,
                                filters.normalizedLimit(),
                                Sort.by(Sort.Direction.DESC, "createdAt"))).getContent()
                : alertRepository.findAll(specification(filters), Sort.by(Sort.Direction.DESC, "createdAt"));
        return alerts.stream()
                .filter(alert -> alertAccessService.canReadDevice(alert.getDeviceId(), userId, role, correlationId))
                .toList();
    }

    private AlertSummaryResponse toSummary(List<Alert> alerts) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (AlertStatus status : AlertStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (AlertSeverity severity : AlertSeverity.values()) {
            bySeverity.put(severity.name(), 0L);
        }
        Map<String, Long> byDevice = new LinkedHashMap<>();
        for (Alert alert : alerts) {
            byStatus.merge(alert.getStatus().name(), 1L, Long::sum);
            bySeverity.merge(alert.getSeverity().name(), 1L, Long::sum);
            byDevice.merge(alert.getDeviceId().toString(), 1L, Long::sum);
        }
        return new AlertSummaryResponse(alerts.size(), byStatus, bySeverity, byDevice, Instant.now());
    }

    private void recordCreated(Alert alert, String eventType) {
        meterRegistry.counter(
                "alert.alerts.created",
                "alertType", safeTag(alert.getAlertType()),
                "severity", alert.getSeverity().name(),
                "eventType", safeTag(eventType)).increment();
        log.info(
                "alertCreated alertId={} deviceId={} sourceEventId={} alertType={} severity={} status={}",
                alert.getId(),
                alert.getDeviceId(),
                alert.getSourceEventId(),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getStatus());
    }

    private void recordDeduplicationHit(Alert alert, String reason) {
        meterRegistry.counter(
                "alert.alerts.deduplicated",
                "reason", safeTag(reason),
                "alertType", safeTag(alert.getAlertType()),
                "severity", alert.getSeverity().name()).increment();
        log.info(
                "alertDeduplicated alertId={} deviceId={} sourceEventId={} deduplicationKey={} reason={}",
                alert.getId(),
                alert.getDeviceId(),
                alert.getSourceEventId(),
                alert.getDeduplicationKey(),
                reason);
    }

    private void recordAcknowledged(Alert alert, UUID actorId, String correlationId) {
        meterRegistry.counter("alert.actions", "action", "acknowledge", "status", "success").increment();
        recordLifecycleDuration("alert.lifecycle.time_to_ack", alert.getCreatedAt(), alert.getAcknowledgedAt(), alert);
        log.info(
                "alertAcknowledged correlationId={} alertId={} deviceId={} sourceEventId={} actorId={} status={}",
                correlationId,
                alert.getId(),
                alert.getDeviceId(),
                alert.getSourceEventId(),
                actorId,
                alert.getStatus());
    }

    private void recordResolved(Alert alert, UUID actorId, String correlationId) {
        meterRegistry.counter("alert.actions", "action", "resolve", "status", "success").increment();
        recordLifecycleDuration("alert.lifecycle.time_to_resolve", alert.getCreatedAt(), alert.getResolvedAt(), alert);
        log.info(
                "alertResolved correlationId={} alertId={} deviceId={} sourceEventId={} actorId={} status={} reasonProvided={}",
                correlationId,
                alert.getId(),
                alert.getDeviceId(),
                alert.getSourceEventId(),
                actorId,
                alert.getStatus(),
                alert.getResolutionReason() != null && !alert.getResolutionReason().isBlank());
    }

    private void recordLifecycleDuration(String metricName, Instant start, Instant end, Alert alert) {
        if (start == null || end == null || end.isBefore(start)) {
            return;
        }
        meterRegistry.timer(
                metricName,
                "alertType", safeTag(alert.getAlertType()),
                "severity", alert.getSeverity().name()).record(Duration.between(start, end));
    }

    @Transactional(readOnly = true)
    public AlertResponse getAlert(UUID id, String userId, String role, String correlationId) {
        Alert alert = load(id);
        alertAccessService.requireReadAccess(alert.getDeviceId(), userId, role, correlationId);
        return toResponse(alert);
    }

    @Transactional
    public AlertResponse acknowledge(UUID id, String userId, String role, String correlationId) {
        Alert alert = load(id);
        alertAccessService.requireReadAccess(alert.getDeviceId(), userId, role, correlationId);
        if (alert.getStatus() == AlertStatus.OPEN) {
            UUID actorId = parseUserId(userId);
            alert.setStatus(AlertStatus.ACKNOWLEDGED);
            alert.setAcknowledgedAt(Instant.now());
            alert.setAcknowledgedBy(actorId);
            saveEvent(alert, "ACKNOWLEDGED", actorId, null, null);
            recordAcknowledged(alert, actorId, correlationId);
        }
        return toResponse(alert);
    }

    @Transactional
    public AlertResponse resolve(UUID id, String reason, String userId, String role, String correlationId) {
        Alert alert = load(id);
        alertAccessService.requireReadAccess(alert.getDeviceId(), userId, role, correlationId);
        if (alert.getStatus() != AlertStatus.RESOLVED) {
            UUID actorId = parseUserId(userId);
            alert.setStatus(AlertStatus.RESOLVED);
            alert.setResolvedAt(Instant.now());
            alert.setResolvedBy(actorId);
            alert.setResolutionReason(normalizeReason(reason));
            saveEvent(alert, "RESOLVED", actorId, alert.getResolutionReason(), null);
            recordResolved(alert, actorId, correlationId);
        }
        return toResponse(alert);
    }

    private Alert createAlert(
            UUID deviceId,
            UUID sourceEventId,
            UUID anomalyId,
            UUID riskScoreId,
            String ruleId,
            Instant calculationWindowStart,
            Instant calculationWindowEnd,
            String calculationInputHash,
            String deduplicationKey,
            String type,
            String title,
            String description,
            AlertSeverity severity,
            String eventType,
            Object payload,
            Instant createdAt,
            String correlationId) {
        Alert alert = new Alert();
        alert.setId(UUID.randomUUID());
        alert.setDeviceId(deviceId);
        alert.setAccountId(alertAccessService.resolveDeviceAccountId(deviceId, correlationId));
        alert.setSourceEventId(sourceEventId);
        alert.setAnomalyId(anomalyId);
        alert.setRiskScoreId(riskScoreId);
        alert.setRuleId(ruleId);
        alert.setCalculationWindowStart(calculationWindowStart);
        alert.setCalculationWindowEnd(calculationWindowEnd);
        alert.setCalculationInputHash(calculationInputHash);
        alert.setDeduplicationKey(deduplicationKey);
        alert.setAlertType(type);
        alert.setTitle(title);
        alert.setDescription(description);
        alert.setSeverity(severity);
        alert.setStatus(AlertStatus.OPEN);
        alert.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        Alert saved = alertRepository.saveAndFlush(alert);
        saveEvent(saved, eventType, null, null, payload);
        recordCreated(saved, eventType);
        notificationDeliveryService.dispatchAlertCreated(saved);
        return saved;
    }

    private Alert findOrCreateAlert(String deduplicationKey, UUID sourceEventId, AlertFactory factory) {
        synchronized (deduplicationService.lockFor(deduplicationKey)) {
            return deduplicationService.findByDeduplicationKey(deduplicationKey)
                    .orElseGet(() -> {
                        try {
                            return factory.create();
                        } catch (DataIntegrityViolationException exception) {
                            Alert existing = deduplicationService.findExisting(sourceEventId, deduplicationKey)
                                    .orElseThrow(() -> exception);
                            recordDeduplicationHit(existing, "constraint_race");
                            return existing;
                        }
                    });
        }
    }

    private Alert load(UUID id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
    }

    private void saveEvent(Alert alert, String eventType, UUID actorId, String reason, Object details) {
        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        AlertEvent event = new AlertEvent();
        event.setId(eventId);
        event.setAlertId(alert.getId());
        event.setEventType(eventType);
        event.setActorId(actorId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("deviceId", alert.getDeviceId());
        context.put("sourceEventId", alert.getSourceEventId());
        context.put("anomalyId", alert.getAnomalyId());
        context.put("riskScoreId", alert.getRiskScoreId());
        context.put("ruleId", alert.getRuleId());
        context.put("calculationWindowStart", alert.getCalculationWindowStart());
        context.put("calculationWindowEnd", alert.getCalculationWindowEnd());
        context.put("calculationInputHash", alert.getCalculationInputHash());
        context.put("details", details == null ? Map.of() : details);
        event.setPayloadJson(writeJson(new AlertEventPayload(
                eventId,
                alert.getId(),
                actorId,
                eventType,
                createdAt,
                alert.getStatus().name(),
                reason,
                context)));
        event.setCreatedAt(createdAt);
        alertEventRepository.save(event);
    }

    private Specification<Alert> specification(AlertListFilters filters) {
        return Specification.allOf(
                equalsIfPresent("deviceId", filters.deviceId()),
                equalsIfPresent("status", filters.status()),
                equalsIfPresent("severity", filters.severity()),
                equalsIfPresent("alertType", filters.alertType()),
                equalsIfPresent("ruleId", filters.ruleId()),
                createdAtFrom(filters.from()),
                createdAtTo(filters.to()));
    }

    private <T> Specification<Alert> equalsIfPresent(String field, T value) {
        return value == null ? null : (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(field), value);
    }

    private Specification<Alert> createdAtFrom(Instant from) {
        return from == null ? null : (root, query, criteriaBuilder) -> criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    private Specification<Alert> createdAtTo(Instant to) {
        return to == null ? null : (root, query, criteriaBuilder) -> criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), to);
    }

    private AlertSeverity anomalySeverity(String severity) {
        return switch (valueOrDefault(severity, "MEDIUM").toUpperCase()) {
            case "CRITICAL" -> AlertSeverity.CRITICAL;
            case "HIGH" -> AlertSeverity.HIGH;
            case "LOW" -> AlertSeverity.INFO;
            default -> AlertSeverity.WARNING;
        };
    }

    private AlertSeverity riskSeverity(String level) {
        return switch (valueOrDefault(level, "LOW").toUpperCase()) {
            case "CRITICAL" -> AlertSeverity.CRITICAL;
            case "HIGH" -> AlertSeverity.HIGH;
            case "MEDIUM" -> AlertSeverity.WARNING;
            default -> AlertSeverity.INFO;
        };
    }

    private String title(String type) {
        return switch (type) {
            case "POWER_SPIKE" -> "Power spike detected";
            case "CONSUMPTION_GROWTH" -> "Consumption growth detected";
            case "LONG_CYCLE" -> "Long cycle detected";
            default -> "Anomaly detected";
        };
    }

    private AlertResponse toResponse(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getDeviceId(),
                alert.getAccountId(),
                alert.getSourceEventId(),
                alert.getAnomalyId(),
                alert.getRiskScoreId(),
                alert.getRuleId(),
                alert.getCalculationWindowStart(),
                alert.getCalculationWindowEnd(),
                alert.getCalculationInputHash(),
                alert.getAlertType(),
                alert.getTitle(),
                alert.getDescription(),
                alert.getSeverity().name(),
                alert.getStatus().name(),
                alert.getCreatedAt(),
                alert.getAcknowledgedAt(),
                alert.getAcknowledgedBy(),
                alert.getResolvedAt(),
                alert.getResolvedBy(),
                alert.getAssignedTo(),
                alert.getResolutionReason());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write alert event JSON", e);
        }
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Resolved without a reason.";
        }
        String trimmed = reason.trim();
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }

    private int normalizedPage(Integer page) {
        return page == null ? 0 : Math.max(0, page);
    }

    private int normalizedSize(Integer size) {
        if (size == null) {
            return 50;
        }
        return Math.max(1, Math.min(size, 200));
    }

    private UUID parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record AlertEventPayload(
            UUID eventId,
            UUID alertId,
            UUID actorId,
            String action,
            Instant timestamp,
            String status,
            String reason,
            Map<String, Object> context) {
    }

    @FunctionalInterface
    private interface AlertFactory {
        Alert create();
    }
}
