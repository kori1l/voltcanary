package com.voltcanary.analytics;

import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.producer.AnalyticsOutboxReconciliationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.analytics.authorization.enabled=false",
        "app.analytics.outbox.reconciliation.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:analytics-outbox-reconciliation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class AnalyticsOutboxReconciliationTest {

    @Autowired
    private AnalyticsOutboxReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private MlClient mlClient;

    @Test
    void resetsFailedAndStaleProcessingRowsForReplay() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        UUID failedId = insertOutbox("FAILED", now.minusSeconds(600), null);
        UUID processingId = insertOutbox("PROCESSING", now.minusSeconds(60), now.minusSeconds(600));
        UUID recentFailedId = insertOutbox("FAILED", now.minusSeconds(60), null);

        var result = reconciliationService.reconcileDueRows(now);

        assertThat(result.failedReset()).isEqualTo(1);
        assertThat(result.processingReset()).isEqualTo(1);
        assertThat(status(failedId)).isEqualTo("PENDING");
        assertThat(status(processingId)).isEqualTo("PENDING");
        assertThat(status(recentFailedId)).isEqualTo("FAILED");
        assertThat(nextAttemptAt(failedId)).isEqualTo(now.plusSeconds(30));
        assertThat(nextAttemptAt(processingId)).isEqualTo(now.plusSeconds(30));
        assertThat(lockedAt(processingId)).isNull();
        assertThat(lastError(failedId)).isNull();
    }

    private UUID insertOutbox(String status, Instant createdAt, Instant lockedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into analytics_event_outbox (
                    id,
                    event_key,
                    topic,
                    message_key,
                    payload_json,
                    status,
                    attempts,
                    last_error,
                    created_at,
                    published_at,
                    next_attempt_at,
                    locked_at,
                    locked_by
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?)
                """,
                id,
                "event:" + id,
                "anomaly.detected.v1",
                id.toString(),
                "{}",
                status,
                5,
                "temporary publish failure",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                lockedAt == null ? null : Timestamp.from(lockedAt),
                lockedAt == null ? null : "test-worker");
        return id;
    }

    private String status(UUID id) {
        return jdbcTemplate.queryForObject("select status from analytics_event_outbox where id = ?", String.class, id);
    }

    private Instant nextAttemptAt(UUID id) {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "select next_attempt_at from analytics_event_outbox where id = ?",
                Timestamp.class,
                id);
        return timestamp.toInstant();
    }

    private Instant lockedAt(UUID id) {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "select locked_at from analytics_event_outbox where id = ?",
                Timestamp.class,
                id);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String lastError(UUID id) {
        return jdbcTemplate.queryForObject("select last_error from analytics_event_outbox where id = ?", String.class, id);
    }
}
