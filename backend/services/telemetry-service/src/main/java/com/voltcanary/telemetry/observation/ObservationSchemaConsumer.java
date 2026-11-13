package com.voltcanary.telemetry.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceBindingSchemaEvent;
import com.voltcanary.common.events.telemetry.SourceBindingSchemaPin;
import com.voltcanary.vcl.VclParser;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Explicit envelope on the new topic. A schema arrival cannot assert current ACL or capture completeness. */
@Component
public class ObservationSchemaConsumer {
    private final ObservationSchemas schemas;
    private final ObservationCaptureProperties properties;
    private final ObjectMapper mapper;
    private final VclParser parser = new VclParser();
    public ObservationSchemaConsumer(ObservationSchemas schemas, ObservationCaptureProperties properties, ObjectMapper mapper) {
        this.schemas = schemas; this.properties = properties; this.mapper = mapper;
    }
    @KafkaListener(topics = "source.binding-schema.v1",
            groupId = "${spring.kafka.consumer.group-id}-#{@observationCaptureProperties.schemaGroup()}",
            containerFactory = "observationSchemaKafkaListenerContainerFactory", autoStartup = "${app.observations.consume-enabled:false}")
    public void consume(String wire) {
        try {
            var root = parser.readJson(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fields(root, Set.of("eventId", "eventType", "eventVersion", "occurredAt", "correlationId", "producer", "accountId", "payload"));
            if (!"SourceBindingSchemaPublished".equals(root.path("eventType").textValue()) || !root.path("eventVersion").isIntegralNumber()
                    || root.path("eventVersion").asLong() != 1 || !"device-service".equals(root.path("producer").textValue())
                    || !root.path("correlationId").isTextual() || root.path("correlationId").textValue().length() > 160) invalid();
            var payload = root.path("payload");
            fields(payload, Set.of("pin", "canonicalDefinition", "sourceActive"));
            fields(payload.path("pin"), Set.of("bindingId", "accountId", "deviceId", "externalSourceId", "schemaRevision", "snapshotId", "definitionDigest"));
            if (!payload.path("pin").path("schemaRevision").isIntegralNumber() || !payload.path("sourceActive").isBoolean()
                    || !payload.path("canonicalDefinition").isTextual()) invalid();
            var pin = mapper.treeToValue(payload.path("pin"), SourceBindingSchemaPin.class);
            if (!pin.accountId().toString().equals(root.path("accountId").textValue())) invalid();
            var event = new SourceBindingSchemaEvent(UUID.fromString(root.path("eventId").textValue()), "v1", pin,
                    payload.path("canonicalDefinition").textValue(), payload.path("sourceActive").asBoolean(),
                    Instant.parse(root.path("occurredAt").textValue()), "device-service");
            if (properties.captures(pin.bindingId())) schemas.apply(event);
        } catch (ObservationCaptureUnavailable failure) { throw failure; }
        catch (RuntimeException | java.io.IOException failure) { throw new ObservationCaptureUnavailable("OBSERVATION_SCHEMA_INVALID"); }
    }
    private static void fields(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !expected.equals(actual)) invalid();
    }
    private static void invalid() { throw new ObservationCaptureUnavailable("OBSERVATION_SCHEMA_INVALID"); }
}
