package com.voltcanary.common.events.telemetry;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TelemetryReceivedEvent(
        UUID eventId,
        UUID deviceId,
        String sourceId,
        Instant timestamp,
        Map<String, Object> metrics,
        Instant receivedAt) {
}
