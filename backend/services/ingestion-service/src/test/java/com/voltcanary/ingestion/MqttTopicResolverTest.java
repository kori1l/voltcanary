package com.voltcanary.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.ingestion.adapter.mqtt.MqttTopicResolver;
import org.junit.jupiter.api.Test;

class MqttTopicResolverTest {

    private final MqttTopicResolver resolver = new MqttTopicResolver();

    @Test
    void extractsSourceId() {
        assertThat(resolver.sourceId("voltcanary/sources/sim-fridge-001/telemetry")).contains(
            "sim-fridge-001"
        );
    }

    @Test
    void rejectsUnsupportedTopic() {
        assertThat(resolver.sourceId("voltcanary/devices/sim-fridge-001/telemetry")).isEmpty();
    }
}
