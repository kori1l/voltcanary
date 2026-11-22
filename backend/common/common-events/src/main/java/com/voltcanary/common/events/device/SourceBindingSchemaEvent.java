package com.voltcanary.common.events.device;

import com.voltcanary.common.events.telemetry.SourceBindingSchemaPin;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Sanitized source journal publication; no adapter credentials, raw configuration or runtime readiness. */
public record SourceBindingSchemaEvent(
    UUID eventId,
    String eventVersion,
    SourceBindingSchemaPin pin,
    String canonicalDefinition,
    boolean sourceActive,
    Instant occurredAt,
    String producer
) {
    public SourceBindingSchemaEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(pin);
        Objects.requireNonNull(occurredAt);
        if (
            !"v1".equals(eventVersion) ||
            !"device-service".equals(producer) ||
            canonicalDefinition == null ||
            canonicalDefinition.length() > 262_144
        ) throw new IllegalArgumentException("Invalid binding schema publication");
    }

    public record Payload(
        SourceBindingSchemaPin pin,
        String canonicalDefinition,
        boolean sourceActive
    ) {}

    /** New topic wire shape uses the shared explicit envelope; existing raw/runtime topics stay compatible. */
    public com.voltcanary.common.events.EventEnvelope<Payload> envelope() {
        return new com.voltcanary.common.events.EventEnvelope<>(
            eventId,
            "SourceBindingSchemaPublished",
            1,
            occurredAt,
            eventId.toString(),
            producer,
            pin.accountId(),
            new Payload(pin, canonicalDefinition, sourceActive)
        );
    }

    @Override
    public String toString() {
        return "SourceBindingSchemaEvent[eventId=" + eventId + ", pin=" + pin + "]";
    }
}
