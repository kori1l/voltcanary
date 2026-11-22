package com.voltcanary.alert.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcessedEventService {

    private final JdbcTemplate jdbcTemplate;

    public boolean markIfNew(String sourceName, UUID eventId) {
        if (eventId == null) {
            return true;
        }
        return (
            com.voltcanary.common.web.JdbcInsertIfAbsent.insert(
                jdbcTemplate,
                "processed_events",
                java.util.List.of("id", "source_name", "event_id", "processed_at"),
                java.util.List.of("source_name", "event_id"),
                UUID.randomUUID(),
                sourceName,
                eventId,
                Timestamp.from(Instant.now())
            ) == 1
        );
    }
}
