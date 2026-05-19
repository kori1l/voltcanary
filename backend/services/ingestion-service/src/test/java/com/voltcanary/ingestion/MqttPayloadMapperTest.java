package com.voltcanary.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.ingestion.mqtt.MqttPayloadMapper;
import com.voltcanary.ingestion.pipeline.IngestionProtocol;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MqttPayloadMapperTest {

    @Test
    void mapsPayloadToIncomingMessage() {
        String payload = """
                {
                  "timestamp": "2026-05-19T12:30:00Z",
                  "metrics": {
                    "activePowerW": 120.5,
                    "voltageV": 230.2
                  }
                }
                """;

        var message = new MqttPayloadMapper(new ObjectMapper())
                .map("sim-fridge-001", payload.getBytes(StandardCharsets.UTF_8));

        assertThat(message.sourceId()).isEqualTo("sim-fridge-001");
        assertThat(message.protocol()).isEqualTo(IngestionProtocol.MQTT);
        assertThat(message.metrics()).containsEntry("activePowerW", 120.5);
    }
}
