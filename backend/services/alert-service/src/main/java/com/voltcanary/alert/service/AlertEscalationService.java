package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.config.AlertEscalationProperties;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertEvent;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.repository.AlertEventRepository;
import com.voltcanary.alert.repository.AlertRepository;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertEscalationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEscalationService.class);
    private static final String ESCALATED = "ESCALATED";

    private final AlertRepository alertRepository;
    private final AlertEventRepository alertEventRepository;
    private final AlertEscalationProperties properties;
    private final NotificationDeliveryService notificationDeliveryService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public AlertEscalationService(
            AlertRepository alertRepository,
            AlertEventRepository alertEventRepository,
            AlertEscalationProperties properties,
            NotificationDeliveryService notificationDeliveryService,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper) {
        this.alertRepository = alertRepository;
        this.alertEventRepository = alertEventRepository;
        this.properties = properties;
        this.notificationDeliveryService = notificationDeliveryService;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Scheduled(
            initialDelayString = "${app.alert.escalation.initial-delay-ms:60000}",
            fixedDelayString = "${app.alert.escalation.scan-interval-ms:60000}")
    public void scheduledEscalationScan() {
        if (properties.isEnabled()) {
            try {
                escalateDueAlerts(Instant.now());
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
            saveEscalationEvent(alert, threshold, now);
            notificationDeliveryService.dispatchAlertEscalated(alert);
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

    private void saveEscalationEvent(Alert alert, Duration threshold, Instant now) {
        UUID eventId = UUID.randomUUID();
        AlertEvent event = new AlertEvent();
        event.setId(eventId);
        event.setAlertId(alert.getId());
        event.setEventType(ESCALATED);
        event.setActorId(null);
        event.setCreatedAt(now);
        event.setPayloadJson(writeJson(escalationPayload(eventId, alert, threshold, now)));
        alertEventRepository.save(event);
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
