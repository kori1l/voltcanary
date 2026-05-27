package com.voltcanary.ingestion.audit;

import com.voltcanary.ingestion.config.IngestionProperties;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IngestionAuditService {

    private static final Logger log = LoggerFactory.getLogger(IngestionAuditService.class);

    private final IngestionProperties properties;
    private final ArrayDeque<IngestionAuditEvent> recentEvents = new ArrayDeque<>();

    public IngestionAuditService(IngestionProperties properties) {
        this.properties = properties;
    }

    public synchronized void record(
            String outcome,
            String reason,
            String correlationId,
            UUID deviceId,
            String sourceId,
            UUID eventId) {
        IngestionAuditEvent event = new IngestionAuditEvent(
                Instant.now(),
                outcome,
                reason,
                correlationId,
                deviceId,
                sourceId,
                eventId);
        recentEvents.addLast(event);
        while (recentEvents.size() > properties.getAuditCapacity()) {
            recentEvents.removeFirst();
        }
        log.info(
                "ingestionAudit outcome={} reason={} correlationId={} deviceId={} sourceId={} eventId={}",
                outcome,
                reason,
                correlationId,
                deviceId,
                sourceId,
                eventId);
    }

    public synchronized List<IngestionAuditEvent> recent() {
        return new ArrayList<>(recentEvents);
    }
}
