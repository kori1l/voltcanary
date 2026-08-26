package com.voltcanary.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.ingestion.adapter.http.HttpTelemetryAdapter;
import com.voltcanary.ingestion.adapter.mqtt.MqttPayloadMapper;
import com.voltcanary.ingestion.adapter.mqtt.MqttTelemetryAdapter;
import com.voltcanary.ingestion.adapter.mqtt.MqttTopicResolver;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.ingress.IngressOrchestrator;
import com.voltcanary.ingestion.mqtt.MqttIngress;
import com.voltcanary.ingestion.pipeline.TelemetryIngestionPipeline;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MqttIngressTest {

    @Test
    void routesSupportedMessageThroughAdapterAndPipeline() throws Exception {
        TelemetryIngestionPipeline pipeline = mock(TelemetryIngestionPipeline.class);
        MqttIngress ingress = mqttIngress(pipeline);

        ingress.messageArrived(
                "voltcanary/sources/sim-fridge-001/telemetry",
                new MqttMessage("""
                        {"timestamp":"2026-05-19T12:30:00Z","metrics":{"activePowerW":120.5}}
                        """.getBytes()));

        verify(pipeline).ingest(argThat(message -> "sim-fridge-001".equals(message.sourceId())));
    }

    @Test
    void rejectsUnsupportedTopicBeforePipeline() throws Exception {
        TelemetryIngestionPipeline pipeline = mock(TelemetryIngestionPipeline.class);
        MqttIngress ingress = mqttIngress(pipeline);

        ingress.messageArrived(
                "voltcanary/devices/sim-fridge-001/telemetry",
                new MqttMessage("""
                        {"timestamp":"2026-05-19T12:30:00Z","metrics":{"activePowerW":120.5}}
                        """.getBytes()));

        verifyNoInteractions(pipeline);
    }

    @Test
    void rejectsInvalidPayloadBeforePipeline() throws Exception {
        TelemetryIngestionPipeline pipeline = mock(TelemetryIngestionPipeline.class);
        MqttIngress ingress = mqttIngress(pipeline);

        ingress.messageArrived(
                "voltcanary/sources/sim-fridge-001/telemetry",
                new MqttMessage("not-json".getBytes()));

        verifyNoInteractions(pipeline);
    }

    private MqttIngress mqttIngress(TelemetryIngestionPipeline pipeline) {
        IngestionProperties properties = new IngestionProperties();
        properties.getMqtt().setBrokerUrl("tcp://localhost:1883");
        properties.getMqtt().setClientId("test-client");
        properties.getMqtt().setTopicFilter("voltcanary/sources/+/telemetry");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MqttTelemetryAdapter adapter = new MqttTelemetryAdapter(
                new MqttTopicResolver(),
                new MqttPayloadMapper(new ObjectMapper()));
        IngressOrchestrator orchestrator = new IngressOrchestrator(
                pipeline,
                new HttpTelemetryAdapter(),
                properties,
                meterRegistry);
        return new MqttIngress(adapter, orchestrator, meterRegistry, properties);
    }
}
