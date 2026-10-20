package com.voltcanary.telemetry.variable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.config.VariableProcessingProperties;
import com.voltcanary.telemetry.producer.TelemetryDeadLetterPublisher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PendingVariableEvents {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConfiguredTelemetryHandler handler;
    private final TelemetryDeadLetterPublisher deadLetters;
    private final VariableProcessingProperties properties;
    private final TransactionTemplate transactions;

    public PendingVariableEvents(JdbcTemplate jdbc, ObjectMapper mapper, ConfiguredTelemetryHandler handler,
            TelemetryDeadLetterPublisher deadLetters, VariableProcessingProperties properties,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.handler = handler;
        this.deadLetters = deadLetters;
        this.properties = properties;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void retryPending() {
        if (!properties.isEnabled()) return;
        var ids = jdbc.query("select event_id from variable_pending_events where status='PENDING' order by updated_at,event_id limit 50",
                (row, index) -> row.getObject(1, UUID.class));
        for (UUID id : ids) {
            try {
                transactions.executeWithoutResult(status -> {
                    var records = jdbc.queryForList("select * from variable_pending_events where event_id=? for update", id);
                    if (records.isEmpty() || !"PENDING".equals(records.getFirst().get("status"))) return;
                    var record = records.getFirst();
                    TelemetryReceivedEvent event = decode(record.get("payload_json").toString());
                    int attempts = ((Number) record.get("attempts")).intValue();
                    if (attempts >= 30) {
                        deadLetters.publish(event, "processing_bundle_unavailable", List.of("BUNDLE_UNAVAILABLE"));
                        jdbc.update("update variable_pending_events set status='DLQ',updated_at=? where event_id=?",
                                Timestamp.from(Instant.now()), id);
                        return;
                    }
                    handler.process(event);
                    jdbc.update("update variable_pending_events set attempts=attempts+1,updated_at=? where event_id=?",
                            Timestamp.from(Instant.now()), id);
                });
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Pending variable event retry failed eventId={} cause={}",
                        id, failure.getClass().getSimpleName());
            }
        }
    }

    private TelemetryReceivedEvent decode(String json) {
        try { return mapper.readValue(json, TelemetryReceivedEvent.class); }
        catch (Exception failure) { throw new IllegalStateException("Invalid parked event", failure); }
    }
}
