package com.voltcanary.common.events.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MeasurementsAcceptedEvent(
    UUID eventId,
    String eventVersion,
    UUID sourceEventId,
    UUID accountId,
    UUID deviceId,
    UUID dataSourceId,
    String sourceId,
    Instant recordedAt,
    Instant receivedAt,
    ProcessingContext processingContext,
    String eligibility,
    List<Measurement> measurements,
    Instant occurredAt,
    String producer
) {
    public MeasurementsAcceptedEvent {
        measurements = List.copyOf(measurements);
    }

    public record Measurement(
        UUID variableId,
        int revision,
        String key,
        String origin,
        double value,
        String unit,
        String quality,
        UUID lineageId
    ) {}
}
