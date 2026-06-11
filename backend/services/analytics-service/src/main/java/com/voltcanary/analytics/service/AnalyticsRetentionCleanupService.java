package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsRetentionProperties;
import com.voltcanary.analytics.scheduling.SchedulerLockService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsRetentionCleanupService {

    private static final String LOCK_NAME = "analytics.retention-cleanup";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(30);

    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsRetentionProperties properties;
    private final SchedulerLockService schedulerLockService;

    public AnalyticsRetentionCleanupService(
            JdbcTemplate jdbcTemplate,
            AnalyticsRetentionProperties properties,
            SchedulerLockService schedulerLockService) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.schedulerLockService = schedulerLockService;
    }

    @Scheduled(cron = "${app.analytics.retention.cleanup-cron:0 20 3 * * *}")
    public void cleanup() {
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
        deleteOlderThan("device_baselines", "calculated_at", now, properties.getBaselines());
        deleteOlderThan("feature_sets", "window_end", now, properties.getFeatureSets());
        deleteOlderThan("prediction_results", "created_at", now, properties.getPredictions());
        deleteOlderThan("anomalies", "detected_at", now, properties.getAnomalies());
        deleteOlderThan("risk_scores", "calculated_at", now, properties.getRiskScores());
        deleteOlderThan("recommendations", "created_at", now, properties.getRecommendations());
        deleteOlderThan("analytics_jobs", "created_at", now, properties.getJobs());
        jdbcTemplate.update(
                "DELETE FROM analytics_event_outbox WHERE status IN ('PUBLISHED', 'FAILED') AND created_at < ?",
                Timestamp.from(now.minus(properties.getOutbox())));
    }

    private void deleteOlderThan(String table, String column, Instant now, Duration retention) {
        jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE " + column + " < ?",
                Timestamp.from(now.minus(retention)));
    }
}
