package com.voltcanary.alert.service;

import com.voltcanary.alert.config.AlertRetentionProperties;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.repository.AlertEventRepository;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.repository.NotificationAttemptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AlertRetentionService.class);

    private final AlertRetentionProperties properties;
    private final AlertRepository alertRepository;
    private final AlertEventRepository alertEventRepository;
    private final NotificationAttemptRepository notificationAttemptRepository;
    private final MeterRegistry meterRegistry;

    public AlertRetentionService(
            AlertRetentionProperties properties,
            AlertRepository alertRepository,
            AlertEventRepository alertEventRepository,
            NotificationAttemptRepository notificationAttemptRepository,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.alertRepository = alertRepository;
        this.alertEventRepository = alertEventRepository;
        this.notificationAttemptRepository = notificationAttemptRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    @Scheduled(
            initialDelayString = "${app.alert.retention.initial-delay-ms:300000}",
            fixedDelayString = "${app.alert.retention.scan-interval-ms:3600000}")
    public void cleanupExpiredRecords() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        long attempts = notificationAttemptRepository.deleteByCreatedAtBefore(now.minus(properties.getNotificationAttemptTtl()));
        long events = alertEventRepository.deleteByCreatedAtBefore(now.minus(properties.getEventTtl()));
        long alerts = alertRepository.deleteByStatusAndResolvedAtBefore(AlertStatus.RESOLVED, now.minus(properties.getResolvedAlertTtl()));
        record("notification_attempts", attempts);
        record("alert_events", events);
        record("resolved_alerts", alerts);
        if (attempts > 0 || events > 0 || alerts > 0) {
            log.info("alertRetentionCleanup notificationAttempts={} alertEvents={} resolvedAlerts={}", attempts, events, alerts);
        }
    }

    private void record(String recordType, long count) {
        if (count > 0) {
            meterRegistry.counter("alert.retention.deleted", "recordType", recordType).increment(count);
        }
    }
}
