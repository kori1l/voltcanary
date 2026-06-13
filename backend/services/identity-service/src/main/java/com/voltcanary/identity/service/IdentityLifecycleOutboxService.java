package com.voltcanary.identity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.identity.IdentityAccountLifecycleEvent;
import com.voltcanary.identity.domain.User;
import com.voltcanary.identity.domain.UserStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IdentityLifecycleOutboxService {

    public static final String TOPIC = "identity.account-lifecycle.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdentityLifecycleOutboxService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void enqueueAccountLifecycle(User user) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        IdentityAccountLifecycleEvent event = new IdentityAccountLifecycleEvent(
                eventId,
                "v1",
                user.getId(),
                user.getEmail(),
                accountStatus(user.getStatus()),
                now,
                null,
                "identity-service",
                user.getId());
        jdbcTemplate.update("""
                insert into identity_event_outbox
                    (id, event_key, topic, message_key, payload_json, status, attempts, next_attempt_at, created_at)
                values (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """,
                eventId,
                TOPIC + ":" + eventId,
                TOPIC,
                user.getId().toString(),
                payload(event),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private String accountStatus(UserStatus userStatus) {
        if (userStatus == UserStatus.ACTIVE) {
            return "ACTIVE";
        }
        if (userStatus == UserStatus.DELETED) {
            return "DELETED";
        }
        return "DISABLED";
    }

    private String payload(IdentityAccountLifecycleEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize identity lifecycle event", exception);
        }
    }
}
