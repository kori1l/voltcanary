package com.voltcanary.alert.service;

import com.voltcanary.alert.deduplication.AlertDeduplicationService;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.notification.port.AlertNotificationPort;
import com.voltcanary.alert.notification.port.AlertNotificationRequest;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertCreationWrites {
    private final AlertRepository alertRepository;
    private final AlertDeduplicationService deduplicationService;
    private final AlertMaintenanceSuppressionService maintenanceSuppressionService;
    private final DeviceHistoryDeletion deletions;
    private final AlertNotificationPort alertNotificationPort;
    private final AlertEventWrites events;
    private final MeterRegistry meterRegistry;

    @Transactional
    public Alert handleAnomaly(AnomalyDetectedEvent event, UUID accountId) {
        if (!deletions.writable(event.deviceId())) return null;
        AlertSeverity severity = anomalySeverity(event.severity());
        String type = valueOrDefault(event.type(), "ANOMALY");
        String deduplicationKey = event.variableEvidence() == null
                ? deduplicationService.key(event.deviceId(), type, severity, event.detectedAt())
                : deduplicationService.anomalyKey(event, severity);
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
        var activeInWindow = event.variableEvidence() == null
                ? deduplicationService.findActiveInWindow(event.deviceId(), type, severity, event.detectedAt())
                : deduplicationService.activeAnomaly(event, severity);
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
                () -> createAlert(accountId, 
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
    public Alert handleRiskScoreChanged(RiskScoreChangedEvent event, UUID accountId) {
        if (!deletions.writable(event.deviceId())) return null;
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
                () -> createAlert(accountId, 
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
    private Alert createAlert(
            UUID accountId,
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
        alert.setAccountId(accountId);
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
        UUID alertEventId = events.saveEvent(saved, eventType, null, null, payload);
        recordCreated(saved, eventType);
        alertNotificationPort.alertCreated(AlertNotificationRequest.fromAlert(
                saved,
                alertEventId,
                "ALERT_CREATED",
                saved.getCreatedAt()));
        return saved;
    }
    private Alert findOrCreateAlert(String deduplicationKey, UUID sourceEventId, AlertFactory factory) {
        synchronized (deduplicationService.lockFor(deduplicationKey)) {
            return deduplicationService.findByDeduplicationKey(deduplicationKey)
                    .orElseGet(factory::create);
        }
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
    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
    private String safeTag(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
    @FunctionalInterface
    private interface AlertFactory { Alert create(); }

}
