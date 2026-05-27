package com.voltcanary.ingestion.audit;

import java.time.Instant;
import java.util.UUID;

public record IngestionAuditEvent(
        Instant occurredAt,
        String outcome,
        String reason,
        String correlationId,
        UUID deviceId,
        String sourceId,
        UUID eventId) {
}
