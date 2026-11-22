package com.voltcanary.telemetry.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TelemetryOutboxService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void enqueue(String eventKey, String topic, String messageKey, Object payload) {
        enqueue(eventKey, topic, messageKey, payload, null);
    }

    public void enqueue(String eventKey, String topic, String messageKey, Object payload, UUID readingId) {
        Instant now = Instant.now();
        com.voltcanary.common.web.JdbcInsertIfAbsent.insert(jdbcTemplate, "telemetry_event_outbox",
                java.util.List.of("id", "event_key", "topic", "message_key", "payload_json", "status", "attempts", "next_attempt_at", "created_at", "reading_id"), java.util.List.of("event_key"),
                UUID.randomUUID(), eventKey, topic, messageKey, writeJson(payload), "PENDING", 0,
                Timestamp.from(now), Timestamp.from(now), readingId);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to write telemetry outbox payload", exception);
        }
    }
}
