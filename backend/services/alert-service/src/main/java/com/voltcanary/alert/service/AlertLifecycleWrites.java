package com.voltcanary.alert.service;

import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.repository.AlertRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertLifecycleWrites {

    private final AlertRepository alertRepository;
    private final jakarta.persistence.EntityManager entityManager;
    private final AlertEventWrites events;
    private final MeterRegistry meterRegistry;

    @Transactional
    public AlertResponse acknowledge(UUID id, UUID actorId, String correlationId) {
        Alert alert = locked(id);
        if (alert.getStatus() == AlertStatus.OPEN) {
            alert.setStatus(AlertStatus.ACKNOWLEDGED);
            alert.setAcknowledgedAt(Instant.now());
            alert.setAcknowledgedBy(actorId);
            events.saveEvent(alert, "ACKNOWLEDGED", actorId, null, null);
            recordAcknowledged(alert, actorId, correlationId);
        }
        return AlertViews.response(alert);
    }

    @Transactional
    public AlertResponse resolve(UUID id, String reason, UUID actorId, String correlationId) {
        Alert alert = locked(id);
        if (alert.getStatus() != AlertStatus.RESOLVED) {
            alert.setStatus(AlertStatus.RESOLVED);
            alert.setResolvedAt(Instant.now());
            alert.setResolvedBy(actorId);
            alert.setResolutionReason(normalizeReason(reason));
            events.saveEvent(alert, "RESOLVED", actorId, alert.getResolutionReason(), null);
            recordResolved(alert, actorId, correlationId);
        }
        return AlertViews.response(alert);
    }

    private void recordAcknowledged(Alert alert, UUID actorId, String correlationId) {
        meterRegistry
            .counter("alert.actions", "action", "acknowledge", "status", "success")
            .increment();
        recordLifecycleDuration(
            "alert.lifecycle.time_to_ack",
            alert.getCreatedAt(),
            alert.getAcknowledgedAt(),
            alert
        );
        log.info(
            "alertAcknowledged correlationId={} alertId={} deviceId={} sourceEventId={} actorId={} status={}",
            correlationId,
            alert.getId(),
            alert.getDeviceId(),
            alert.getSourceEventId(),
            actorId,
            alert.getStatus()
        );
    }

    private void recordResolved(Alert alert, UUID actorId, String correlationId) {
        meterRegistry
            .counter("alert.actions", "action", "resolve", "status", "success")
            .increment();
        recordLifecycleDuration(
            "alert.lifecycle.time_to_resolve",
            alert.getCreatedAt(),
            alert.getResolvedAt(),
            alert
        );
        log.info(
            "alertResolved correlationId={} alertId={} deviceId={} sourceEventId={} actorId={} status={} reasonProvided={}",
            correlationId,
            alert.getId(),
            alert.getDeviceId(),
            alert.getSourceEventId(),
            actorId,
            alert.getStatus(),
            alert.getResolutionReason() != null && !alert.getResolutionReason().isBlank()
        );
    }

    private void recordLifecycleDuration(
        String metricName,
        Instant start,
        Instant end,
        Alert alert
    ) {
        if (start == null || end == null || end.isBefore(start)) {
            return;
        }
        meterRegistry
            .timer(
                metricName,
                "alertType",
                safeTag(alert.getAlertType()),
                "severity",
                alert.getSeverity().name()
            )
            .record(Duration.between(start, end));
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

    private Alert locked(UUID id) {
        var alert = alertRepository
            .lockById(id)
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found")
            );
        entityManager.refresh(alert);
        return alert;
    }
}
