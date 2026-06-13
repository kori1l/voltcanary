package com.voltcanary.telemetry.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TelemetryOperationalMetrics {

    private final JdbcTemplate jdbcTemplate;

    public TelemetryOperationalMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        for (String status : new String[] {"PENDING", "PROCESSING", "FAILED"}) {
            Gauge.builder("telemetry.outbox.depth", this, ignored -> count("""
                            select count(*)
                            from telemetry_event_outbox
                            where status = ?
                            """, status))
                    .tag("status", status)
                    .register(meterRegistry);
        }
        Gauge.builder("telemetry.outbox.oldest.pending.age.seconds", this, ignored -> oldestPendingAgeSeconds())
                .register(meterRegistry);
        Gauge.builder("telemetry.outbox.retry.count", this, ignored -> count("""
                        select coalesce(sum(attempts), 0)
                        from telemetry_event_outbox
                        where status in ('PENDING', 'PROCESSING', 'FAILED')
                        """))
                .register(meterRegistry);
        Gauge.builder("telemetry.outbox.stuck.processing", this, ignored -> count("""
                        select count(*)
                        from telemetry_event_outbox
                        where status = 'PROCESSING'
                          and locked_at < ?
                        """, Timestamp.from(Instant.now().minus(Duration.ofMinutes(2)))))
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
                    from telemetry_event_outbox
                    where status = 'PENDING'
                    order by created_at asc
                    limit 1
                    """, Timestamp.class);
            return timestamp == null ? 0 : Math.max(0, Duration.between(timestamp.toInstant(), Instant.now()).toSeconds());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
