package com.voltcanary.common.events;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String eventVersion,
        String source,
        Instant occurredAt,
        T payload) {
}
