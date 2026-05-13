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
        Instant createdAt) {
}
