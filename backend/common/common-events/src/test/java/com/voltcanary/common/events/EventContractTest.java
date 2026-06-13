package com.voltcanary.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.alert.AlertCreatedEvent;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.device.DeviceRegistryChangedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import com.voltcanary.common.events.identity.IdentityAccountLifecycleEvent;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void eventEnvelopeSerializesAsJson() throws Exception {
        UUID eventId = UUID.randomUUID();
        TelemetryReceivedEvent payload = new TelemetryReceivedEvent(
                eventId,
                UUID.randomUUID(),
                "source-1",
                Instant.parse("2026-05-13T12:00:00Z"),
                Map.of("voltage", 230.5),
                Instant.parse("2026-05-13T12:00:01Z"));

        EventEnvelope<TelemetryReceivedEvent> envelope = new EventEnvelope<>(
                eventId,
                "telemetry.received",
                1,
                Instant.parse("2026-05-13T12:00:01Z"),
                "corr-1",
                "ingestion-service",
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                payload);

        String json = objectMapper.writeValueAsString(envelope);

        assertThat(json).contains("\"eventType\":\"telemetry.received\"");
        assertThat(json).contains("\"eventVersion\":1");
        assertThat(json).contains("\"correlationId\":\"corr-1\"");
        assertThat(json).contains("\"producer\":\"ingestion-service\"");
        assertThat(json).contains("\"accountId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"");
        assertThat(json).contains("\"deviceId\"");
        assertThat(json).contains("\"voltage\":230.5");
    }

    @Test
    void allPublishedEventRecordsExposeEnvelopeMetadataFields() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-13T12:00:00Z");

        assertMetadata(new TelemetryReceivedEvent(
                eventId, deviceId, "source-1", now, Map.of(), now, "v1", "corr-1", "ingestion-service", accountId, now));
        assertMetadata(new TelemetryValidatedEvent(
                eventId, deviceId, "source-1", now, Map.of(), List.of(), now, "v1", "corr-1", "telemetry-service", accountId, now));
        assertMetadata(new AnomalyDetectedEvent(
                eventId, deviceId, UUID.randomUUID(), null, null, null, null, "POWER_SPIKE", "HIGH", 0.91, "Spike", now,
                "v1", "corr-1", "analytics-service", accountId, now));
        assertMetadata(new RiskScoreChangedEvent(
                eventId, deviceId, UUID.randomUUID(), null, null, null, 0.2, 0.7, "MEDIUM", now,
                "v1", "corr-1", "analytics-service", accountId, now));
        assertMetadata(new AlertCreatedEvent(
                eventId, UUID.randomUUID(), deviceId, "HIGH", "OPEN", "Voltage spike", now,
                "v1", "corr-1", "alert-service", accountId, now));
        assertMetadata(new DeviceRegistryChangedEvent(
                eventId, "v1", "DEVICE", "UPDATED", accountId, deviceId, deviceId, "ACTIVE", now, "corr-1", "device-service"));
        assertMetadata(new IdentityAccountLifecycleEvent(
                eventId, "v1", accountId, "user@example.com", "ACTIVE", now, "corr-1", "identity-service", accountId));
    }

    private void assertMetadata(Object event) throws Exception {
        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"eventId\"");
        assertThat(json).contains("\"eventVersion\"");
        assertThat(json).contains("\"correlationId\"");
        assertThat(json).contains("\"producer\"");
        assertThat(json).contains("\"accountId\"");
        assertThat(json).contains("\"occurredAt\"");
    }
}
