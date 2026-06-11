package com.voltcanary.common.events.identity;

import java.time.Instant;
import java.util.UUID;

public record IdentityAccountLifecycleEvent(
        UUID eventId,
        String eventVersion,
        UUID userId,
        String email,
        String status,
        Instant occurredAt) {
}
