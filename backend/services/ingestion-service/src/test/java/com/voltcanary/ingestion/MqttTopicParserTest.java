package com.voltcanary.ingestion;

import com.voltcanary.ingestion.mqtt.MqttTopicParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MqttTopicParserTest {

    private final MqttTopicParser parser = new MqttTopicParser();

    @Test
    void extractsSourceId() {
        assertThat(parser.sourceId("voltcanary/sources/sim-fridge-001/telemetry"))
                .contains("sim-fridge-001");
    }

    @Test
    void rejectsUnsupportedTopic() {
        assertThat(parser.sourceId("voltcanary/devices/sim-fridge-001/telemetry"))
                .isEmpty();
    }
}
