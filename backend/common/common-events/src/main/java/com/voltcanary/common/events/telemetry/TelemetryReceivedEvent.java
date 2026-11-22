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
    Instant occurredAt,
    ProcessingContext processingContext,
    SourceBindingSchemaPin bindingSchemaPin,
    @com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
    )
    SourcePosition sourcePosition
) {
    public TelemetryReceivedEvent(
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
        Instant occurredAt,
        ProcessingContext processingContext,
        SourceBindingSchemaPin bindingSchemaPin
    ) {
        this(
            eventId,
            deviceId,
            sourceId,
            timestamp,
            metrics,
            receivedAt,
            eventVersion,
            correlationId,
            producer,
            accountId,
            occurredAt,
            processingContext,
            bindingSchemaPin,
            null
        );
    }

    public TelemetryReceivedEvent(
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
        Instant occurredAt,
        ProcessingContext processingContext
    ) {
        this(
            eventId,
            deviceId,
            sourceId,
            timestamp,
            metrics,
            receivedAt,
            eventVersion,
            correlationId,
            producer,
            accountId,
            occurredAt,
            processingContext,
            null
        );
    }

    public TelemetryReceivedEvent(
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
        Instant occurredAt
    ) {
        this(
            eventId,
            deviceId,
            sourceId,
            timestamp,
            metrics,
            receivedAt,
            eventVersion,
            correlationId,
            producer,
            accountId,
            occurredAt,
            null
        );
    }

    public TelemetryReceivedEvent(
        UUID eventId,
        UUID deviceId,
        String sourceId,
        Instant timestamp,
        Map<String, Object> metrics,
        Instant receivedAt
    ) {
        this(
            eventId,
            deviceId,
            sourceId,
            timestamp,
            metrics,
            receivedAt,
            "v1",
            null,
            null,
            null,
            timestamp
        );
    }
}
