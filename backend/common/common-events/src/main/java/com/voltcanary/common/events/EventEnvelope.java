package com.voltcanary.common.events;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        String producer,
        UUID accountId,
        T payload) {

    public EventEnvelope(
            UUID eventId,
            String eventType,
            String eventVersion,
            String source,
            Instant occurredAt,
            T payload) {
        this(eventId, eventType, parseVersion(eventVersion), occurredAt, null, source, null, payload);
    }

    private static int parseVersion(String eventVersion) {
        if (eventVersion == null || eventVersion.isBlank()) {
            return 1;
        }
        String normalized = eventVersion.startsWith("v") ? eventVersion.substring(1) : eventVersion;
        return Integer.parseInt(normalized);
    }
}
