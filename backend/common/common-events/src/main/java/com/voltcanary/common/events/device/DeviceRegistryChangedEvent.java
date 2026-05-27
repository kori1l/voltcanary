package com.voltcanary.common.events.device;

import java.time.Instant;
import java.util.UUID;

public record DeviceRegistryChangedEvent(
        UUID eventId,
        String eventVersion,
        String resourceType,
        String action,
        UUID accountId,
        UUID deviceId,
        UUID resourceId,
        String status,
        Instant occurredAt) {
}
