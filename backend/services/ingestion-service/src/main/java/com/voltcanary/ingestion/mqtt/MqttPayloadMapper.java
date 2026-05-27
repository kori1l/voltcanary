package com.voltcanary.ingestion.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.ingestion.pipeline.IncomingTelemetryMessage;
import com.voltcanary.ingestion.pipeline.IngestionProtocol;
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
        try {
            String rawPayload = new String(payload, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(rawPayload);
            Instant timestamp = Instant.parse(root.path("timestamp").asText());
            Map<String, Object> metrics = objectMapper.convertValue(root.path("metrics"), new TypeReference<>() {
            });
            return new IncomingTelemetryMessage(
                    sourceId,
                    timestamp,
                    metrics,
                    IngestionProtocol.MQTT,
                    rawPayload,
                    null,
                    null,
                    null,
                    null,
                    false);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid MQTT telemetry payload", exception);
        }
    }
}
