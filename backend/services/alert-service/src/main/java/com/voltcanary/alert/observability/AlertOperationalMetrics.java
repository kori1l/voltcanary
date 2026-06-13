package com.voltcanary.alert.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AlertOperationalMetrics {

    private final JdbcTemplate jdbcTemplate;

    public AlertOperationalMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        for (String status : new String[] {"PENDING", "RETRYING", "PROCESSING", "FAILED", "DISABLED"}) {
            Gauge.builder("alert.notifications.attempts.depth", this, ignored -> count("""
                            select count(*)
                            from notification_attempts
                            where status = ?
                            """, status))
                    .tag("status", status)
                    .register(meterRegistry);
        }
        for (String status : new String[] {"RECEIVED", "PROCESSING", "FAILED", "PROCESSED"}) {
            Gauge.builder("alert.analytics.inbox.depth", this, ignored -> count("""
                            select count(*)
                            from alert_analytics_event_inbox
                            where status = ?
                            """, status))
                    .tag("status", status)
                    .register(meterRegistry);
        }
        Gauge.builder("alert.notifications.oldest.pending.age.seconds", this, ignored -> oldestNotificationAgeSeconds())
                .register(meterRegistry);
        Gauge.builder("alert.notifications.retry.count", this, ignored -> count("""
                        select coalesce(sum(retry_count), 0)
                        from notification_attempts
                        where status in ('PENDING', 'RETRYING', 'PROCESSING', 'FAILED')
                        """))
                .register(meterRegistry);
        Gauge.builder("alert.notifications.stuck.processing", this, ignored -> count("""
                        select count(*)
                        from notification_attempts
                        where status = 'PROCESSING'
                          and locked_at < ?
                        """, Timestamp.from(Instant.now().minus(Duration.ofMinutes(2)))))
                .register(meterRegistry);
        Gauge.builder("alert.analytics.inbox.stuck.processing", this, ignored -> count("""
                        select count(*)
                        from alert_analytics_event_inbox
                        where status = 'PROCESSING'
                          and locked_at < ?
                        """, Timestamp.from(Instant.now().minus(Duration.ofMinutes(2)))))
                .register(meterRegistry);
        Gauge.builder("alert.scheduler.locks.active", this, ignored -> count("""
                        select count(*)
                        from scheduler_locks
                        where locked_until > ?
                        """, Timestamp.from(Instant.now())))
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

    private double oldestNotificationAgeSeconds() {
        try {
            Timestamp timestamp = jdbcTemplate.queryForObject("""
                    select created_at
                    from notification_attempts
                    where status in ('PENDING', 'RETRYING')
                    order by created_at asc
                    limit 1
                    """, Timestamp.class);
            return timestamp == null ? 0 : Math.max(0, Duration.between(timestamp.toInstant(), Instant.now()).toSeconds());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
