package com.voltcanary.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.alert.AlertCreatedEvent;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
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
                "v1",
                "ingestion-service",
                Instant.parse("2026-05-13T12:00:01Z"),
                payload);

        String json = objectMapper.writeValueAsString(envelope);

        assertThat(json).contains("\"eventType\":\"telemetry.received\"");
        assertThat(json).contains("\"eventVersion\":\"v1\"");
        assertThat(json).contains("\"deviceId\"");
        assertThat(json).contains("\"voltage\":230.5");
    }

    @Test
    void allInitialEventRecordsAreAvailable() {
        UUID eventId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-13T12:00:00Z");

        assertThat(new TelemetryReceivedEvent(eventId, deviceId, "source-1", now, Map.of(), now)).isNotNull();
        assertThat(new TelemetryValidatedEvent(eventId, deviceId, "source-1", now, Map.of(), List.of(), now))
                .isNotNull();
        assertThat(new AnomalyDetectedEvent(eventId, deviceId, UUID.randomUUID(), "HIGH", 0.91, "Spike", now))
                .isNotNull();
        assertThat(new RiskScoreChangedEvent(eventId, deviceId, 0.2, 0.7, "MEDIUM", now)).isNotNull();
        assertThat(new AlertCreatedEvent(eventId, UUID.randomUUID(), deviceId, "HIGH", "OPEN", "Voltage spike", now))
                .isNotNull();
    }
}
