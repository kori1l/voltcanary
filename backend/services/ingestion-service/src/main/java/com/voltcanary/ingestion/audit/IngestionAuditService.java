package com.voltcanary.ingestion.audit;

import com.voltcanary.ingestion.config.IngestionProperties;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionAuditService {

    private final IngestionProperties properties;
    private final ArrayDeque<IngestionAuditEvent> recentEvents = new ArrayDeque<>();

    public synchronized void record(
        String outcome,
        String reason,
        String correlationId,
        UUID deviceId,
        String sourceId,
        UUID eventId
    ) {
        IngestionAuditEvent event = new IngestionAuditEvent(
            Instant.now(),
            outcome,
            reason,
            correlationId,
            deviceId,
            sourceId,
            eventId
        );
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
            eventId
        );
    }

    public synchronized List<IngestionAuditEvent> recent() {
        return new ArrayList<>(recentEvents);
    }
}
