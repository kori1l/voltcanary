package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.config.AlertEscalationProperties;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertEvent;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.notification.port.AlertNotificationPort;
import com.voltcanary.alert.notification.port.AlertNotificationRequest;
import com.voltcanary.alert.repository.AlertEventRepository;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.scheduling.SchedulerLockService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertEscalationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEscalationService.class);
    private static final String ESCALATED = "ESCALATED";
    private static final String LOCK_NAME = "alert.escalation-scan";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);

    private final AlertRepository alertRepository;
    private final AlertEventRepository alertEventRepository;
    private final AlertEscalationProperties properties;
    private final AlertNotificationPort alertNotificationPort;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final SchedulerLockService schedulerLockService;

    public AlertEscalationService(
            AlertRepository alertRepository,
            AlertEventRepository alertEventRepository,
            AlertEscalationProperties properties,
            AlertNotificationPort alertNotificationPort,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            SchedulerLockService schedulerLockService) {
        this.alertRepository = alertRepository;
        this.alertEventRepository = alertEventRepository;
        this.properties = properties;
        this.alertNotificationPort = alertNotificationPort;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.schedulerLockService = schedulerLockService;
    }

    @Scheduled(
            initialDelayString = "${app.alert.escalation.initial-delay-ms:60000}",
            fixedDelayString = "${app.alert.escalation.scan-interval-ms:60000}")
    public void scheduledEscalationScan() {
        if (properties.isEnabled()) {
            try {
                if (!schedulerLockService.acquire(LOCK_NAME, LOCK_LEASE)) {
                    return;
                }
                try {
                    escalateDueAlerts(Instant.now());
                } finally {
                    schedulerLockService.release(LOCK_NAME);
                }
            } catch (DataAccessException exception) {
                log.debug("alertEscalationScanSkipped reason={}", exception.getClass().getSimpleName());
            }
        }
    }

    @Transactional
    public int escalateDueAlerts(Instant now) {
        if (!properties.isEnabled()) {
            return 0;
        }
        Instant effectiveNow = now == null ? Instant.now() : now;
        int escalated = 0;
        escalated += escalateSeverity(AlertSeverity.WARNING, properties.getWarningAfter(), effectiveNow);
        escalated += escalateSeverity(AlertSeverity.HIGH, properties.getHighAfter(), effectiveNow);
        escalated += escalateSeverity(AlertSeverity.CRITICAL, properties.getCriticalAfter(), effectiveNow);
        return escalated;
    }

    private int escalateSeverity(AlertSeverity severity, Duration threshold, Instant now) {
        List<Alert> candidates = alertRepository.findByStatusAndSeverityAndCreatedAtBeforeOrderByCreatedAtAsc(
                AlertStatus.OPEN,
                severity,
                now.minus(threshold));
        int escalated = 0;
        for (Alert alert : candidates) {
            if (alertEventRepository.existsByAlertIdAndEventType(alert.getId(), ESCALATED)) {
                continue;
            }
            var alertEventId = saveEscalationEvent(alert, threshold, now);
            if (alertEventId.isEmpty()) {
                continue;
            }
            alertNotificationPort.alertEscalated(AlertNotificationRequest.fromAlert(
                    alert,
                    alertEventId.get(),
                    "ALERT_ESCALATED",
                    now));
            meterRegistry.counter(
                    "alert.escalations",
                    "severity", severity.name(),
                    "alertType", safeTag(alert.getAlertType())).increment();
            log.info(
                    "alertEscalated alertId={} deviceId={} severity={} ageSeconds={}",
                    alert.getId(),
                    alert.getDeviceId(),
                    severity,
                    Duration.between(alert.getCreatedAt(), now).toSeconds());
            escalated++;
        }
        return escalated;
    }

    private java.util.Optional<UUID> saveEscalationEvent(Alert alert, Duration threshold, Instant now) {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent();
        event.setId(eventId);
        event.setAlertId(alert.getId());
        event.setEventType(ESCALATED);
        event.setActorId(null);
        event.setCreatedAt(now);
        event.setDeduplicationKey(alert.getId() + ":" + ESCALATED);
        event.setPayloadJson(writeJson(escalationPayload(eventId, alert, threshold, now)));
        try {
            alertEventRepository.saveAndFlush(event);
            return java.util.Optional.of(eventId);
        } catch (DataIntegrityViolationException ignored) {
            return java.util.Optional.empty();
        }
    }

    private Map<String, Object> escalationPayload(UUID eventId, Alert alert, Duration threshold, Instant now) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("deviceId", alert.getDeviceId());
        context.put("sourceEventId", alert.getSourceEventId());
        context.put("anomalyId", alert.getAnomalyId());
        context.put("riskScoreId", alert.getRiskScoreId());
        context.put("ruleId", alert.getRuleId());
        context.put("calculationWindowStart", alert.getCalculationWindowStart());
        context.put("calculationWindowEnd", alert.getCalculationWindowEnd());
        context.put("calculationInputHash", alert.getCalculationInputHash());
        context.put("thresholdSeconds", threshold.toSeconds());
        context.put("ageSeconds", Duration.between(alert.getCreatedAt(), now).toSeconds());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("alertId", alert.getId());
        payload.put("actorId", null);
        payload.put("action", ESCALATED);
        payload.put("timestamp", now);
        payload.put("status", alert.getStatus().name());
        payload.put("reason", "Alert was not acknowledged before escalation threshold.");
        payload.put("context", context);
        return payload;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to write escalation event JSON", exception);
        }
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
