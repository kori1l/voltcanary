package com.voltcanary.common.events.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceBindingSchemaEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceBindingSchemaPinTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void oldWireAndConstructorsRetainNullPinAndNumericProcessingCompatibility() throws Exception {
        var event = new TelemetryReceivedEvent(UUID.randomUUID(), UUID.randomUUID(), "legacy", Instant.now(), Map.of("temperatureC", 3), Instant.now());
        var wire = mapper.valueToTree(event);
        ((com.fasterxml.jackson.databind.node.ObjectNode) wire).remove(java.util.List.of("processingContext", "bindingSchemaPin"));
        var decoded = mapper.treeToValue(wire, TelemetryReceivedEvent.class);
        assertNull(decoded.processingContext());
        assertNull(decoded.bindingSchemaPin());
        var processing = new ProcessingContext(UUID.randomUUID(), 2, "a".repeat(64), "v1");
        var numeric = new TelemetryReceivedEvent(event.eventId(), event.deviceId(), event.sourceId(), event.timestamp(), event.metrics(),
                event.receivedAt(), "v1", null, "ingestion-service", UUID.randomUUID(), event.timestamp(), processing);
        assertEquals(processing, numeric.processingContext());
        assertNull(numeric.bindingSchemaPin());
    }

    @Test void schemaPublicationAndRawEventRoundTripExactPinsWithoutReadinessClaims() throws Exception {
        var pin = pin();
        var event = new TelemetryReceivedEvent(UUID.randomUUID(), pin.deviceId(), pin.externalSourceId(), Instant.now(), Map.of("temperatureC", 3),
                Instant.now(), "v1", null, "ingestion-service", pin.accountId(), Instant.now(), null, pin);
        assertEquals(pin, mapper.readValue(mapper.writeValueAsBytes(event), TelemetryReceivedEvent.class).bindingSchemaPin());
        var schema = new SourceBindingSchemaEvent(UUID.randomUUID(), "v1", pin, "{}", true, Instant.now(), "device-service");
        assertEquals(schema, mapper.readValue(mapper.writeValueAsBytes(schema), SourceBindingSchemaEvent.class));
        var envelope = mapper.readTree(mapper.writeValueAsBytes(schema.envelope()));
        assertEquals(1, envelope.path("eventVersion").asInt());
        assertEquals(pin.accountId().toString(), envelope.path("accountId").asText());
        assertEquals(schema.canonicalDefinition(), envelope.at("/payload/canonicalDefinition").asText());
        assertFalse(mapper.writeValueAsString(pin).contains("Ready"));
        assertFalse(pin.toString().contains(pin.externalSourceId()));
        assertFalse(schema.toString().contains("canonicalDefinition"));
    }

    @Test void invalidBoundsAndUnsupportedEventVersionCannotEnterTheTypedEnvelope() {
        var pin = pin();
        for (long revision : new long[]{0, -1, Long.MAX_VALUE}) assertThrows(IllegalArgumentException.class,
                () -> new SourceBindingSchemaPin(pin.bindingId(), pin.accountId(), pin.deviceId(), pin.externalSourceId(), revision, pin.snapshotId(), pin.definitionDigest()));
        assertThrows(IllegalArgumentException.class, () -> new SourceBindingSchemaEvent(UUID.randomUUID(), "v2", pin, "{}", true, Instant.now(), "device-service"));
        assertThrows(IllegalArgumentException.class, () -> new SourceBindingSchemaEvent(UUID.randomUUID(), "v1", pin, " ".repeat(262_145), true, Instant.now(), "device-service"));
    }

    private static SourceBindingSchemaPin pin() {
        return new SourceBindingSchemaPin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "private-source", 1, UUID.randomUUID(), "a".repeat(64));
    }
}
