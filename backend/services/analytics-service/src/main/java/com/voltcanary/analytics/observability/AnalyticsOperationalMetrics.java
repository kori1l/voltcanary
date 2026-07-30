package com.voltcanary.analytics.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsOperationalMetrics {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsOperationalMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        for (String status : new String[] {"PENDING", "PROCESSING", "FAILED"}) {
            Gauge.builder("analytics.outbox.depth", this, ignored -> count("""
                            select count(*)
                            from analytics_event_outbox
                            where status = ?
                            """, status))
                    .tag("status", status)
                    .register(meterRegistry);
        }
        for (String status : new String[] {"PENDING", "RUNNING", "FAILED"}) {
            Gauge.builder("analytics.jobs.depth", this, ignored -> count("""
                            select count(*)
                            from analytics_jobs
                            where status = ?
                            """, status))
                    .tag("status", status)
                    .register(meterRegistry);
        }
        Gauge.builder("analytics.outbox.oldest.pending.age.seconds", this, ignored -> oldestPendingAgeSeconds())
                .register(meterRegistry);
        Gauge.builder("analytics.outbox.retry.count", this, ignored -> count("""
                        select coalesce(sum(attempts), 0)
                        from analytics_event_outbox
                        where status in ('PENDING', 'PROCESSING', 'FAILED')
                        """))
                .register(meterRegistry);
        Gauge.builder("analytics.outbox.stuck.processing", this, ignored -> count("""
                        select count(*)
                        from analytics_event_outbox
                        where status = 'PROCESSING'
                          and locked_at < ?
                        """, Timestamp.from(Instant.now().minus(Duration.ofMinutes(2)))))
                .register(meterRegistry);
        Gauge.builder("analytics.jobs.stale.running", this, ignored -> count("""
                        select count(*)
                        from analytics_jobs
                        where status = 'RUNNING'
                          and locked_at is not null
                          and locked_at < ?
                        """, Timestamp.from(Instant.now().minus(Duration.ofMinutes(15)))))
                .register(meterRegistry);
        Gauge.builder("analytics.scheduler.locks.active", this, ignored -> count("""
                        select count(*)
                        from scheduler_locks
                        where locked_until > ?
                        """, Timestamp.from(Instant.now())))
                .register(meterRegistry);
        for (String status : new String[] {"READY", "FAILED", "VALIDATING", "ARCHIVED"}) {
            Gauge.builder("analytics.ml.dataset.versions", this, ignored -> count("""
                            select count(*)
                            from ml_dataset_versions
                            where status = ?
                            """, status))
                    .tag("status", status)
                    .register(meterRegistry);
        }
        Gauge.builder("analytics.ml.dataset.validation.rejected.rows", this, ignored -> count("""
                        select coalesce(sum(rejected_rows), 0)
                        from ml_validation_reports
                        """))
                .register(meterRegistry);
        Gauge.builder("analytics.ml.feature.snapshots.generated", this, ignored -> count("""
                        select count(*)
                        from ml_feature_snapshots
                        """))
                .register(meterRegistry);
        Gauge.builder("analytics.ml.feature.snapshots.rejected.rows", this, ignored -> count("""
                        select coalesce(sum(rejected_row_count), 0)
                        from ml_feature_snapshots
                        """))
                .register(meterRegistry);
        for (String status : new String[] {"QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED"}) {
            Gauge.builder("analytics.ml.training.runs", this, ignored -> count("""
                            select count(*)
                            from ml_training_runs
                            where status = ?
                            """, status))
                    .tag("status", status)
                    .register(meterRegistry);
        }
        Gauge.builder("analytics.ml.inference.fallback.rate", this, ignored -> ratio("""
                        select count(*)
                        from prediction_results
                        where shadow_prediction = false
                          and fallback_reason is not null
                          and fallback_reason <> ''
                        """, """
                        select count(*)
                        from prediction_results
                        where shadow_prediction = false
                        """))
                .register(meterRegistry);
        Gauge.builder("analytics.ml.drift.active.feature_schema_hashes", this, ignored -> count("""
                        select count(distinct feature_schema_hash)
                        from ml_model_versions
                        where status = 'ACTIVE'
                          and feature_schema_hash is not null
                          and feature_schema_hash <> ''
                        """))
                .register(meterRegistry);
    }

    private double count(String sql, Object... args) {
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
            return value == null ? 0 : value;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private double oldestPendingAgeSeconds() {
        try {
            Timestamp timestamp = jdbcTemplate.queryForObject("""
                    select created_at
                    from analytics_event_outbox
                    where status = 'PENDING'
                    order by created_at asc
                    limit 1
                    """, Timestamp.class);
            return timestamp == null ? 0 : Math.max(0, Duration.between(timestamp.toInstant(), Instant.now()).toSeconds());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private double ratio(String numeratorSql, String denominatorSql) {
        double denominator = count(denominatorSql);
        if (denominator <= 0) {
            return 0;
        }
        return count(numeratorSql) / denominator;
    }
}
