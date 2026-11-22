package com.voltcanary.alert.service;

import com.voltcanary.alert.config.AlertRetentionProperties;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.notification.repository.NotificationAttemptRepository;
import com.voltcanary.alert.repository.AlertEventRepository;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.scheduling.SchedulerLockService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertRetentionService {

    private static final String LOCK_NAME = "alert.retention-cleanup";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(30);

    private final AlertRetentionProperties properties;
    private final AlertRepository alertRepository;
    private final AlertEventRepository alertEventRepository;
    private final NotificationAttemptRepository notificationAttemptRepository;
    private final MeterRegistry meterRegistry;
    private final SchedulerLockService schedulerLockService;

    @Scheduled(
        initialDelayString = "${app.alert.retention.initial-delay-ms:300000}",
        fixedDelayString = "${app.alert.retention.scan-interval-ms:3600000}"
    )
    public void cleanupExpiredRecords() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!schedulerLockService.acquire(LOCK_NAME, LOCK_LEASE)) {
            return;
        }
        try {
            cleanupRecords();
        } finally {
            schedulerLockService.release(LOCK_NAME);
        }
    }

    private void cleanupRecords() {
        Instant now = Instant.now();
        long attempts = notificationAttemptRepository.deleteByCreatedAtBefore(
            now.minus(properties.getNotificationAttemptTtl())
        );
        long events = alertEventRepository.deleteByCreatedAtBefore(
            now.minus(properties.getEventTtl())
        );
        long alerts = alertRepository.deleteByStatusAndResolvedAtBefore(
            AlertStatus.RESOLVED,
            now.minus(properties.getResolvedAlertTtl())
        );
        record("notification_attempts", attempts);
        record("alert_events", events);
        record("resolved_alerts", alerts);
        if (attempts > 0 || events > 0 || alerts > 0) {
            log.info(
                "alertRetentionCleanup notificationAttempts={} alertEvents={} resolvedAlerts={}",
                attempts,
                events,
                alerts
            );
        }
    }

    private void record(String recordType, long count) {
        if (count > 0) {
            meterRegistry
                .counter("alert.retention.deleted", "recordType", recordType)
                .increment(count);
        }
    }
}
