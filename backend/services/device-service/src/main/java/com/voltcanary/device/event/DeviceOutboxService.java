package com.voltcanary.device.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeviceOutboxService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DeviceOutboxService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void enqueue(String eventKey, String topic, String messageKey, Object payload) {
        Instant now = Instant.now();
        try {
            jdbcTemplate.update("""
                    insert into device_event_outbox
                        (id, event_key, topic, message_key, payload_json, status, attempts, next_attempt_at, created_at)
                    values (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                    """,
                    UUID.randomUUID(),
                    eventKey,
                    topic,
                    messageKey,
                    writeJson(payload),
                    Timestamp.from(now),
                    Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // Domain retries should not enqueue duplicate registry side effects.
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to write device outbox payload", exception);
        }
    }
}
