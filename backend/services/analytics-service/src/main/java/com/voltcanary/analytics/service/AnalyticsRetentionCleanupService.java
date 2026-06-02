package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsRetentionProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsRetentionCleanupService {

    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsRetentionProperties properties;

    public AnalyticsRetentionCleanupService(JdbcTemplate jdbcTemplate, AnalyticsRetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.analytics.retention.cleanup-cron:0 20 3 * * *}")
    @Transactional
    public void cleanup() {
        if (!properties.isEnabled()) {
            return;
        }
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
