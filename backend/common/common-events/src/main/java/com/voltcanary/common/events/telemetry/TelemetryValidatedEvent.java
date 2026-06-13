package com.voltcanary.common.events.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TelemetryValidatedEvent(
        UUID eventId,
        UUID deviceId,
        String sourceId,
        Instant timestamp,
        Map<String, Object> metrics,
        List<String> qualityFlags,
        Instant validatedAt,
        String eventVersion,
        String correlationId,
        String producer,
        UUID accountId,
        Instant occurredAt) {

    public TelemetryValidatedEvent(
            UUID eventId,
            UUID deviceId,
            String sourceId,
            Instant timestamp,
            Map<String, Object> metrics,
            List<String> qualityFlags,
            Instant validatedAt) {
        this(eventId, deviceId, sourceId, timestamp, metrics, qualityFlags, validatedAt, "v1", null, null, null, validatedAt);
    }
}
