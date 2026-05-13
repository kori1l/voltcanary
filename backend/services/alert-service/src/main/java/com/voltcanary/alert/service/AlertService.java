package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.alert.Alert;
import com.voltcanary.alert.alert.AlertEvent;
import com.voltcanary.alert.alert.AlertSeverity;
import com.voltcanary.alert.alert.AlertStatus;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.repository.AlertEventRepository;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {

    private static final DateTimeFormatter HOUR_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final AlertRepository alertRepository;
    private final AlertEventRepository alertEventRepository;
    private final ObjectMapper objectMapper;

    public AlertService(AlertRepository alertRepository, AlertEventRepository alertEventRepository, ObjectMapper objectMapper) {
        this.alertRepository = alertRepository;
        this.alertEventRepository = alertEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Alert handleAnomaly(AnomalyDetectedEvent event) {
        AlertSeverity severity = anomalySeverity(event.severity());
        String type = valueOrDefault(event.type(), "ANOMALY");
        String deduplicationKey = deduplicationKey(event.deviceId(), type, severity, event.detectedAt());
        return alertRepository.findFirstByDeduplicationKeyOrderByCreatedAtDesc(deduplicationKey)
                .orElseGet(() -> createAlert(
                        event.deviceId(),
                        deduplicationKey,
                        type,
                        title(type),
                        event.description(),
                        severity,
                        "ANOMALY_DETECTED",
                        event,
                        event.detectedAt()));
    }

    @Transactional
    public Alert handleRiskScoreChanged(RiskScoreChangedEvent event) {
        AlertSeverity severity = riskSeverity(event.riskLevel());
        if (severity == AlertSeverity.INFO) {
            return null;
        }
        String type = "RISK_" + valueOrDefault(event.riskLevel(), "UNKNOWN");
        String deduplicationKey = deduplicationKey(event.deviceId(), type, severity, event.changedAt());
        return alertRepository.findFirstByDeduplicationKeyOrderByCreatedAtDesc(deduplicationKey)
                .orElseGet(() -> createAlert(
                        event.deviceId(),
                        deduplicationKey,
                        type,
                        "Risk score changed to " + event.riskLevel(),
                        "Risk score is " + event.currentScore() + " (" + event.riskLevel() + ").",
                        severity,
                        "RISK_SCORE_CHANGED",
                        event,
                        event.changedAt()));
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> listAlerts() {
        return alertRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AlertResponse getAlert(UUID id) {
        return alertRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
    }

    @Transactional
    public AlertResponse acknowledge(UUID id) {
        Alert alert = load(id);
        if (alert.getStatus() == AlertStatus.OPEN) {
            alert.setStatus(AlertStatus.ACKNOWLEDGED);
            alert.setAcknowledgedAt(Instant.now());
            saveEvent(alert.getId(), "ACKNOWLEDGED", alert);
        }
        return toResponse(alert);
    }

    @Transactional
    public AlertResponse resolve(UUID id) {
        Alert alert = load(id);
        if (alert.getStatus() != AlertStatus.RESOLVED) {
            alert.setStatus(AlertStatus.RESOLVED);
            alert.setResolvedAt(Instant.now());
            saveEvent(alert.getId(), "RESOLVED", alert);
        }
        return toResponse(alert);
    }

    private Alert createAlert(
            UUID deviceId,
            String deduplicationKey,
            String type,
            String title,
            String description,
            AlertSeverity severity,
            String eventType,
            Object payload,
            Instant createdAt) {
        Alert alert = new Alert();
        alert.setId(UUID.randomUUID());
        alert.setDeviceId(deviceId);
        alert.setDeduplicationKey(deduplicationKey);
        alert.setAlertType(type);
        alert.setTitle(title);
        alert.setDescription(description);
        alert.setSeverity(severity);
        alert.setStatus(AlertStatus.OPEN);
        alert.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        Alert saved = alertRepository.save(alert);
        saveEvent(saved.getId(), eventType, payload);
        return saved;
    }

    private Alert load(UUID id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
    }

    private void saveEvent(UUID alertId, String eventType, Object payload) {
        AlertEvent event = new AlertEvent();
        event.setId(UUID.randomUUID());
        event.setAlertId(alertId);
        event.setEventType(eventType);
        event.setPayloadJson(writeJson(payload));
        event.setCreatedAt(Instant.now());
        alertEventRepository.save(event);
    }

    private String deduplicationKey(UUID deviceId, String alertType, AlertSeverity severity, Instant occurredAt) {
        return deviceId + ":" + alertType + ":" + severity + ":" + HOUR_FORMATTER.format(occurredAt == null ? Instant.now() : occurredAt);
    }

    private AlertSeverity anomalySeverity(String severity) {
        return switch (valueOrDefault(severity, "MEDIUM").toUpperCase()) {
            case "CRITICAL", "HIGH" -> AlertSeverity.CRITICAL;
            case "LOW" -> AlertSeverity.INFO;
            default -> AlertSeverity.WARNING;
        };
    }

    private AlertSeverity riskSeverity(String level) {
        return switch (valueOrDefault(level, "LOW").toUpperCase()) {
            case "CRITICAL", "HIGH" -> AlertSeverity.CRITICAL;
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
                alert.getAlertType(),
                alert.getTitle(),
                alert.getDescription(),
                alert.getSeverity().name(),
                alert.getStatus().name(),
                alert.getCreatedAt(),
                alert.getAcknowledgedAt(),
                alert.getResolvedAt());
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
}
