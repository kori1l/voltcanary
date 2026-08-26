package com.voltcanary.ingestion.adapter.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MqttPayloadMapper {

    private final ObjectMapper objectMapper;

    public MqttPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public IncomingTelemetryMessage map(String sourceId, byte[] payload) {
        return map(sourceId, null, payload);
    }

    public IncomingTelemetryMessage map(String sourceId, String topic, byte[] payload) {
        try {
            String rawPayload = new String(payload, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(rawPayload);
            Instant timestamp = Instant.parse(root.path("timestamp").asText());
            Map<String, Object> metrics = objectMapper.convertValue(root.path("metrics"), new TypeReference<>() {
            });
            return new IncomingTelemetryMessage(
                    sourceId,
                    null,
                    null,
                    timestamp,
                    Instant.now(),
                    metrics,
                    TelemetryOrigin.mqtt(topic),
                    null,
                    false);
        } catch (Exception exception) {
            throw new MqttPayloadMappingException("Invalid MQTT telemetry payload", exception);
        }
    }
}
