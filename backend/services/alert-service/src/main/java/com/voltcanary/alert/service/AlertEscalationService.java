package com.voltcanary.alert.service;

import com.voltcanary.alert.config.AlertEscalationProperties;
import com.voltcanary.alert.scheduling.SchedulerLockService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertEscalationService {
    private static final String LOCK_NAME = "alert.escalation-scan";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);
    private final AlertEscalationProperties properties;
    private final SchedulerLockService schedulerLockService;
    private final AlertEscalationWrites writes;

    @Scheduled(initialDelayString = "${app.alert.escalation.initial-delay-ms:60000}",
            fixedDelayString = "${app.alert.escalation.scan-interval-ms:60000}")
    public void scheduledEscalationScan() {
        if (!properties.isEnabled()) return;
        try {
            if (!schedulerLockService.acquire(LOCK_NAME, LOCK_LEASE)) return;
            try { escalateDueAlerts(Instant.now()); }
            finally { schedulerLockService.release(LOCK_NAME); }
        } catch (DataAccessException exception) {
            log.warn("alertEscalationScanFailed reasonCode={}", exception.getClass().getSimpleName());
        }
    }

    public int escalateDueAlerts(Instant now) { return writes.escalateDueAlerts(now); }
}
