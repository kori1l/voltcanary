package com.voltcanary.analytics.producer;

import com.voltcanary.analytics.config.AnalyticsOutboxReconciliationProperties;
import com.voltcanary.analytics.scheduling.SchedulerLockService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsOutboxReconciliationService {

    private static final String LOCK_NAME = "analytics-outbox-reconciliation";

    private final AnalyticsOutboxReconciliationProperties properties;
    private final SchedulerLockService schedulerLockService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "#{@analyticsOutboxReconciliationProperties.interval.toMillis()}")
    public void reconcile() {
        if (!properties.isEnabled() || !schedulerLockService.acquire(LOCK_NAME, properties.getLockLease())) {
            return;
        }
        try {
            ReconciliationResult result = reconcileDueRows(Instant.now());
            if (result.total() > 0) {
                log.info(
                        "analyticsOutboxReconciliation failedReset={} processingReset={}",
                        result.failedReset(),
                        result.processingReset());
            }
        } finally {
            schedulerLockService.release(LOCK_NAME);
        }
    }

    public ReconciliationResult reconcileDueRows(Instant now) {
        return transactionTemplate.execute(status -> {
            int failedReset = jdbcTemplate.update("""
                    update analytics_event_outbox
                    set status = 'PENDING',
                        next_attempt_at = ?,
                        locked_at = null,
                        locked_by = null,
                        last_error = null
                    where id in (
                        select id
                        from analytics_event_outbox
                        where status = 'FAILED'
                          and created_at <= ?
                        order by created_at asc
                        limit ?
                    )
                    """,
                    Timestamp.from(now.plus(properties.getRetryDelay())),
                    Timestamp.from(now.minus(properties.getFailedAfter())),
                    Math.max(1, properties.getBatchSize()));
            int processingReset = jdbcTemplate.update("""
                    update analytics_event_outbox
                    set status = 'PENDING',
                        next_attempt_at = ?,
                        locked_at = null,
                        locked_by = null,
                        last_error = null
                    where id in (
                        select id
                        from analytics_event_outbox
                        where status = 'PROCESSING'
                          and locked_at <= ?
                        order by created_at asc
                        limit ?
                    )
                    """,
                    Timestamp.from(now.plus(properties.getRetryDelay())),
                    Timestamp.from(now.minus(properties.getProcessingTimeout())),
                    Math.max(1, properties.getBatchSize()));
            if (failedReset > 0) {
                meterRegistry.counter("analytics.outbox.reconciled", "status", "FAILED").increment(failedReset);
            }
            if (processingReset > 0) {
                meterRegistry.counter("analytics.outbox.reconciled", "status", "PROCESSING").increment(processingReset);
            }
            return new ReconciliationResult(failedReset, processingReset);
        });
    }

    public record ReconciliationResult(int failedReset, int processingReset) {
        int total() {
            return failedReset + processingReset;
        }
    }
}
