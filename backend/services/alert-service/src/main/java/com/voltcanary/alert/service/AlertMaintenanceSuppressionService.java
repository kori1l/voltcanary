package com.voltcanary.alert.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertEvent;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.domain.AlertSuppression;
import com.voltcanary.alert.domain.MaintenanceWindowReadModel;
import com.voltcanary.alert.repository.AlertEventRepository;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.repository.AlertSuppressionRepository;
import com.voltcanary.alert.repository.MaintenanceWindowReadModelRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertMaintenanceSuppressionService {

    private static final Logger log = LoggerFactory.getLogger(AlertMaintenanceSuppressionService.class);

    private final MaintenanceWindowReadModelRepository maintenanceRepository;
    private final AlertSuppressionRepository suppressionRepository;
    private final AlertRepository alertRepository;
    private final AlertEventRepository alertEventRepository;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public AlertMaintenanceSuppressionService(
            MaintenanceWindowReadModelRepository maintenanceRepository,
            AlertSuppressionRepository suppressionRepository,
            AlertRepository alertRepository,
            AlertEventRepository alertEventRepository,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper) {
        this.maintenanceRepository = maintenanceRepository;
        this.suppressionRepository = suppressionRepository;
        this.alertRepository = alertRepository;
        this.alertEventRepository = alertEventRepository;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean suppressesEvent(
            UUID deviceId,
            UUID sourceEventId,
            String sourceEventType,
            Instant from,
            Instant to,
            Instant eventTime) {
        Instant normalizedFrom = from == null ? eventTime : from;
        Instant normalizedTo = normalizeTo(to, normalizedFrom);
        return maintenanceRepository.findActiveOverlapping(deviceId, normalizedFrom, normalizedTo).stream()
                .filter(MaintenanceWindowReadModel::isSuppressAlerts)
                .findFirst()
                .map(window -> {
                    recordSuppression(
                            deviceId,
                            null,
                            sourceEventId,
                            sourceEventType,
                            window,
                            "EVENT_SUPPRESSED_BY_MAINTENANCE",
                            eventTime == null ? Instant.now() : eventTime);
                    meterRegistry.counter("alert.maintenance.suppressions", "outcome", "event_suppressed").increment();
                    log.info(
                            "alertSuppressedByMaintenance deviceId={} sourceEventId={} sourceEventType={} maintenanceWindowId={}",
                            deviceId,
                            sourceEventId,
                            sourceEventType,
                            window.getMaintenanceWindowId());
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public int autoResolveOverlappingAlerts(MaintenanceWindowReadModel window) {
        if (window.getCancelledAt() != null || !window.isSuppressAlerts()) {
            return 0;
        }
        int resolved = 0;
        for (Alert alert : alertRepository.findSuppressibleOverlapping(
                window.getDeviceId(),
                window.getStartAt(),
                window.getEndAt(),
                AlertStatus.RESOLVED)) {
            if (suppressionRepository.existsByAlertIdAndMaintenanceWindowId(alert.getId(), window.getMaintenanceWindowId())) {
                continue;
            }
            alert.setStatus(AlertStatus.RESOLVED);
            alert.setResolvedAt(Instant.now());
            alert.setResolvedBy(null);
            alert.setResolutionReason("Auto-resolved because the device is in a maintenance window.");
            alertRepository.save(alert);
            saveAlertEvent(alert, window);
            recordSuppression(
                    alert.getDeviceId(),
                    alert.getId(),
                    alert.getSourceEventId(),
                    "EXISTING_ALERT",
                    window,
                    "AUTO_RESOLVED_BY_MAINTENANCE",
                    alert.getCreatedAt());
            resolved++;
        }
        if (resolved > 0) {
            meterRegistry.counter("alert.maintenance.suppressions", "outcome", "alerts_auto_resolved").increment(resolved);
            log.info(
                    "alertsAutoResolvedByMaintenance maintenanceWindowId={} deviceId={} resolved={}",
                    window.getMaintenanceWindowId(),
                    window.getDeviceId(),
                    resolved);
        }
        return resolved;
    }

    private void recordSuppression(
            UUID deviceId,
            UUID alertId,
            UUID sourceEventId,
            String sourceEventType,
            MaintenanceWindowReadModel window,
            String reason,
            Instant eventTime) {
        if (sourceEventId != null && suppressionRepository.existsBySourceEventId(sourceEventId)) {
            return;
        }
        AlertSuppression suppression = new AlertSuppression();
        suppression.setId(UUID.randomUUID());
        suppression.setDeviceId(deviceId);
        suppression.setAlertId(alertId);
        suppression.setSourceEventId(sourceEventId);
        suppression.setSourceEventType(sourceEventType);
        suppression.setMaintenanceWindowId(window.getMaintenanceWindowId());
        suppression.setMaintenanceWindowVersion(window.getVersion());
        suppression.setSuppressionReason(reason);
        suppression.setEventTime(eventTime == null ? Instant.now() : eventTime);
        suppression.setCreatedAt(Instant.now());
        try {
            suppressionRepository.saveAndFlush(suppression);
        } catch (DataIntegrityViolationException ignored) {
            // Concurrent replay of the same source event is already audited.
        }
    }

    private void saveAlertEvent(Alert alert, MaintenanceWindowReadModel window) {
        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        AlertEvent event = new AlertEvent();
        event.setId(eventId);
        event.setAlertId(alert.getId());
        event.setEventType("AUTO_RESOLVED_BY_MAINTENANCE");
        event.setPayloadJson(writeJson(new AlertEventPayload(
                eventId,
                alert.getId(),
                "AUTO_RESOLVED_BY_MAINTENANCE",
                createdAt,
                alert.getStatus().name(),
                alert.getResolutionReason(),
                Map.of(
                        "deviceId", alert.getDeviceId(),
                        "maintenanceWindowId", window.getMaintenanceWindowId(),
                        "maintenanceWindowVersion", window.getVersion()))));
        event.setCreatedAt(createdAt);
        alertEventRepository.save(event);
    }

    private Instant normalizeTo(Instant to, Instant from) {
        if (to != null && from != null && to.isAfter(from)) {
            return to;
        }
        Instant base = from == null ? Instant.now() : from;
        return base.plusMillis(1);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to write alert maintenance event JSON", exception);
        }
    }

    private record AlertEventPayload(
            UUID eventId,
            UUID alertId,
            String action,
            Instant timestamp,
            String status,
            String reason,
            Map<String, Object> context) {
    }
}
