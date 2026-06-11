package com.voltcanary.analytics.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProcessedEventService {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean markIfNew(String sourceName, UUID eventId) {
        if (eventId == null) {
            return true;
        }
        try {
            jdbcTemplate.update("""
                    insert into processed_events (id, source_name, event_id, processed_at)
                    values (?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    sourceName,
                    eventId,
                    Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }
}
