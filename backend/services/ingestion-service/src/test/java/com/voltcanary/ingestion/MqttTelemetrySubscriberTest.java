package com.voltcanary.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.mqtt.MqttPayloadMapper;
import com.voltcanary.ingestion.mqtt.MqttTelemetrySubscriber;
import com.voltcanary.ingestion.mqtt.MqttTopicParser;
import com.voltcanary.ingestion.pipeline.TelemetryIngestionPipeline;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MqttTelemetrySubscriberTest {

    @Test
    void callsPipeline() throws Exception {
        TelemetryIngestionPipeline pipeline = mock(TelemetryIngestionPipeline.class);
        IngestionProperties properties = new IngestionProperties();
        properties.getMqtt().setBrokerUrl("tcp://localhost:1883");
        properties.getMqtt().setClientId("test-client");
        properties.getMqtt().setTopicFilter("voltcanary/sources/+/telemetry");
        MqttTelemetrySubscriber subscriber = new MqttTelemetrySubscriber(
                new MqttTopicParser(),
                new MqttPayloadMapper(new ObjectMapper()),
                pipeline,
                properties);

        subscriber.messageArrived(
                "voltcanary/sources/sim-fridge-001/telemetry",
                new MqttMessage("""
                        {"timestamp":"2026-05-19T12:30:00Z","metrics":{"activePowerW":120.5}}
                        """.getBytes()));

        verify(pipeline).ingest(argThat(message -> "sim-fridge-001".equals(message.sourceId())));
    }
}
