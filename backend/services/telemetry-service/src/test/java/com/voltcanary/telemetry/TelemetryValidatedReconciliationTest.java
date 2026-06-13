package com.voltcanary.telemetry;

import com.voltcanary.telemetry.service.TelemetryValidatedReconciliationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.telemetry.authorization.enabled=false",
        "app.telemetry.validation.redis-rate-limit-enabled=false",
        "app.telemetry.reconciliation.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:telemetry-reconciliation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TelemetryValidatedReconciliationTest {

    @Autowired
    private TelemetryValidatedReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void enqueuesMissingValidatedEventForPersistedReadingOnce() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        UUID readingId = insertReading("plug-001", now.minusSeconds(60), now.minusSeconds(90));

        var first = reconciliationService.reconcileMissingValidatedEvents(now);
        var second = reconciliationService.reconcileMissingValidatedEvents(now.plusSeconds(1));

        assertThat(first.enqueued()).isEqualTo(1);
        assertThat(second.enqueued()).isZero();
        Long outboxCount = jdbcTemplate.queryForObject(
                "select count(*) from telemetry_event_outbox where topic = 'telemetry.validated.v1' and reading_id = ?",
                Long.class,
                readingId);
        assertThat(outboxCount).isEqualTo(1);
        String payload = jdbcTemplate.queryForObject(
                "select payload_json from telemetry_event_outbox where reading_id = ?",
                String.class,
                readingId);
        assertThat(payload).contains("activePowerW");
    }

    @Test
    void skipsBackfillSources() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        UUID readingId = insertReading("backfill-csv-001", now.minusSeconds(60), now.minusSeconds(90));

        var result = reconciliationService.reconcileMissingValidatedEvents(now);

        assertThat(result.enqueued()).isZero();
        Long outboxCount = jdbcTemplate.queryForObject(
                "select count(*) from telemetry_event_outbox where reading_id = ?",
                Long.class,
                readingId);
        assertThat(outboxCount).isZero();
    }

    private UUID insertReading(String sourceId, Instant recordedAt, Instant receivedAt) {
        UUID readingId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into telemetry_readings (id, device_id, source_id, recorded_at, received_at, metrics_json)
                values (?, ?, ?, ?, ?, ?)
                """,
                readingId,
                deviceId,
                sourceId,
                Timestamp.from(recordedAt),
                Timestamp.from(receivedAt),
                "{\"activePowerW\":840.2}");
        jdbcTemplate.update("""
                insert into telemetry.measurements
                    (device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, created_at)
                values (?, ?, ?, 'activePowerW', 840.2, 'W', 'OK', ?)
                """,
                deviceId,
                sourceId,
                Timestamp.from(recordedAt),
                Timestamp.from(receivedAt));
        return readingId;
    }
}
