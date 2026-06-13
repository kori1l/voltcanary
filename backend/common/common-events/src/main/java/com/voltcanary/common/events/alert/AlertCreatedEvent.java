package com.voltcanary.common.events.alert;

import java.time.Instant;
import java.util.UUID;

public record AlertCreatedEvent(
        UUID eventId,
        UUID alertId,
        UUID deviceId,
        String severity,
        String status,
        String title,
        Instant createdAt,
        String eventVersion,
        String correlationId,
        String producer,
        UUID accountId,
        Instant occurredAt) {

    public AlertCreatedEvent(
            UUID eventId,
            UUID alertId,
            UUID deviceId,
            String severity,
            String status,
            String title,
            Instant createdAt) {
        this(eventId, alertId, deviceId, severity, status, title, createdAt, "v1", null, null, null, createdAt);
    }
}
