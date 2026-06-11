package com.voltcanary.device.event;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.kafka.publish-enabled", havingValue = "true", matchIfMissing = true)
public class DeviceOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeviceOutboxPublisher.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LEASE_TTL = Duration.ofMinutes(2);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final String publisherId = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();

    public DeviceOutboxPublisher(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            @Qualifier("deviceStringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${app.device.outbox.publish-delay:5s}")
    public void publishPending() {
        for (OutboxMessage event : claimPending()) {
            try {
                kafkaTemplate.send(event.topic(), event.messageKey(), event.payloadJson()).get();
                markPublished(event.id(), event.topic());
            } catch (Exception exception) {
                log.warn("deviceOutboxPublishFailed outboxId={} topic={} reasonCode={}",
                        event.id(),
                        event.topic(),
                        exception.getClass().getSimpleName());
                markFailed(event, exception);
            }
        }
    }

    private List<OutboxMessage> claimPending() {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            Instant staleBefore = now.minus(LEASE_TTL);
            List<UUID> ids = claimIds(now, staleBefore, true);
            if (ids.isEmpty()) {
                return List.of();
            }
            updateClaim(ids, now);
            return loadMessages(ids);
        });
    }

    private List<UUID> claimIds(Instant now, Instant staleBefore, boolean skipLocked) {
        String lockClause = skipLocked ? " for update skip locked" : " for update";
        String sql = """
                select id
                from device_event_outbox
                where (
                    status = 'PENDING'
                    and (next_attempt_at is null or next_attempt_at <= ?)
                ) or (
                    status = 'PROCESSING'
                    and locked_at < ?
                )
                order by created_at asc
                limit ?
                """ + lockClause;
        try {
            return jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    Timestamp.from(now),
                    Timestamp.from(staleBefore),
                    BATCH_SIZE);
        } catch (DataAccessException exception) {
            if (skipLocked) {
                return claimIds(now, staleBefore, false);
            }
            throw exception;
        }
    }

    private void updateClaim(List<UUID> ids, Instant now) {
        jdbcTemplate.update("""
                update device_event_outbox
                set status = 'PROCESSING',
                    locked_at = ?,
                    locked_by = ?,
                    attempts = attempts + 1,
                    last_error = null
                where id in (%s)
                """.formatted(placeholders(ids.size())),
                parameters(ids, Timestamp.from(now), publisherId));
    }

    private List<OutboxMessage> loadMessages(List<UUID> ids) {
        return jdbcTemplate.query("""
                select id, topic, message_key, payload_json, attempts
                from device_event_outbox
                where id in (%s)
                order by created_at asc
                """.formatted(placeholders(ids.size())),
                (rs, rowNum) -> new OutboxMessage(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("payload_json"),
                        rs.getInt("attempts")),
                ids.toArray());
    }

    private void markPublished(UUID id, String topic) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update("""
                update device_event_outbox
                set status = 'PUBLISHED',
                    published_at = ?,
                    locked_at = null,
                    locked_by = null,
                    last_error = null
                where id = ?
                """, Timestamp.from(Instant.now()), id));
        meterRegistry.counter("device.events.published", "topic", topic, "status", "published").increment();
    }

    private void markFailed(OutboxMessage event, Exception exception) {
        String nextStatus = event.attempts() >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
        Instant nextAttemptAt = event.attempts() >= MAX_ATTEMPTS
                ? null
                : Instant.now().plus(RETRY_DELAY.multipliedBy(event.attempts()));
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update("""
                update device_event_outbox
                set status = ?,
                    next_attempt_at = ?,
                    locked_at = null,
                    locked_by = null,
                    last_error = ?
                where id = ?
                """,
                nextStatus,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
                message(exception),
                event.id()));
        meterRegistry.counter("device.events.published", "topic", event.topic(), "status", "failed").increment();
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private Object[] parameters(List<UUID> ids, Object... prefix) {
        Object[] parameters = new Object[prefix.length + ids.size()];
        System.arraycopy(prefix, 0, parameters, 0, prefix.length);
        for (int index = 0; index < ids.size(); index++) {
            parameters[prefix.length + index] = ids.get(index);
        }
        return parameters;
    }

    private String message(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private record OutboxMessage(
            UUID id,
            String topic,
            String messageKey,
            String payloadJson,
            int attempts) {
    }
}
