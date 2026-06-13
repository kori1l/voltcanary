package com.voltcanary.telemetry;

import com.voltcanary.telemetry.producer.TelemetryOutboxPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.telemetry.authorization.enabled=false",
        "app.telemetry.reconciliation.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:telemetry-outbox-replay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TelemetryOutboxReplayTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void pendingRowCommittedBeforeCrashIsPublishedOnReplay() {
        UUID id = insertOutbox("PENDING", null);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(eq("telemetry.validated.v1"), eq("device-1"), eq("{\"eventId\":\"" + id + "\"}")))
                .thenReturn(CompletableFuture.completedFuture(null));
        TelemetryOutboxPublisher publisher = new TelemetryOutboxPublisher(
                jdbcTemplate,
                transactionTemplate,
                kafkaTemplate,
                new SimpleMeterRegistry());

        publisher.publishPending();

        verify(kafkaTemplate).send("telemetry.validated.v1", "device-1", "{\"eventId\":\"" + id + "\"}");
        assertThat(status(id)).isEqualTo("PUBLISHED");
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void staleProcessingRowIsRepublishedAndMarkedPublished() {
        UUID id = insertOutbox("PROCESSING", Instant.now().minusSeconds(600));
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(eq("telemetry.validated.v1"), eq("device-1"), eq("{\"eventId\":\"" + id + "\"}")))
                .thenReturn(CompletableFuture.completedFuture(null));
        TelemetryOutboxPublisher publisher = new TelemetryOutboxPublisher(
                jdbcTemplate,
                transactionTemplate,
                kafkaTemplate,
                new SimpleMeterRegistry());

        publisher.publishPending();

        verify(kafkaTemplate).send("telemetry.validated.v1", "device-1", "{\"eventId\":\"" + id + "\"}");
        assertThat(status(id)).isEqualTo("PUBLISHED");
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(lockedAt(id)).isNull();
        assertThat(publishedAt(id)).isNotNull();
    }

    private UUID insertOutbox(String status, Instant lockedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into telemetry_event_outbox (
                    id,
                    event_key,
                    topic,
                    message_key,
                    payload_json,
                    status,
                    attempts,
                    next_attempt_at,
                    locked_at,
                    locked_by,
                    last_error,
                    created_at,
                    published_at,
                    reading_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, null, ?)
                """,
                id,
                "telemetry:" + id,
                "telemetry.validated.v1",
                "device-1",
                "{\"eventId\":\"" + id + "\"}",
                status,
                1,
                Timestamp.from(Instant.now().minusSeconds(600)),
                lockedAt == null ? null : Timestamp.from(lockedAt),
                lockedAt == null ? null : "crashed-worker",
                Timestamp.from(Instant.now().minusSeconds(900)),
                UUID.randomUUID());
        return id;
    }

    private String status(UUID id) {
        return jdbcTemplate.queryForObject("select status from telemetry_event_outbox where id = ?", String.class, id);
    }

    private int attempts(UUID id) {
        return jdbcTemplate.queryForObject("select attempts from telemetry_event_outbox where id = ?", Integer.class, id);
    }

    private Instant lockedAt(UUID id) {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "select locked_at from telemetry_event_outbox where id = ?",
                Timestamp.class,
                id);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Instant publishedAt(UUID id) {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "select published_at from telemetry_event_outbox where id = ?",
                Timestamp.class,
                id);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
