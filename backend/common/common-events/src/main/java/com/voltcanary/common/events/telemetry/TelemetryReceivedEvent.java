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
        Instant receivedAt,
        String eventVersion,
        String correlationId,
        String producer,
        UUID accountId,
        Instant occurredAt) {

    public TelemetryReceivedEvent(
            UUID eventId,
            UUID deviceId,
            String sourceId,
            Instant timestamp,
            Map<String, Object> metrics,
            Instant receivedAt) {
        this(eventId, deviceId, sourceId, timestamp, metrics, receivedAt, "v1", null, null, null, timestamp);
    }
}
