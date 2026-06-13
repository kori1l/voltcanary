package com.voltcanary.common.events.identity;

import java.time.Instant;
import java.util.UUID;

public record IdentityAccountLifecycleEvent(
        UUID eventId,
        String eventVersion,
        UUID userId,
        String email,
        String status,
        Instant occurredAt,
        String correlationId,
        String producer,
        UUID accountId) {

    public IdentityAccountLifecycleEvent(
            UUID eventId,
            String eventVersion,
            UUID userId,
            String email,
            String status,
            Instant occurredAt) {
        this(eventId, eventVersion, userId, email, status, occurredAt, null, null, userId);
    }
}
