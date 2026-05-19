package com.voltcanary.simulator.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.simulator.generator.GeneratedTelemetryPoint;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MqttTelemetrySender implements TelemetrySender {

    private final ObjectMapper objectMapper;
    private final String brokerUrl;
    private final String topicTemplate;
    private MqttClient client;

    public MqttTelemetrySender(
            ObjectMapper objectMapper,
            @Value("${app.simulator.mqtt.broker-url}") String brokerUrl,
            @Value("${app.simulator.mqtt.topic-template}") String topicTemplate) {
        this.objectMapper = objectMapper;
        this.brokerUrl = brokerUrl;
        this.topicTemplate = topicTemplate;
    }

    @Override
    public boolean supports(String protocol) {
        return "MQTT".equalsIgnoreCase(protocol);
    }

    @Override
    public synchronized void send(GeneratedTelemetryPoint point, String sourceToken) {
        try {
            String topic = topicTemplate.replace("{sourceId}", point.sourceId());
            MqttClient mqttClient = client();
            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                    "timestamp", point.timestamp(),
                    "metrics", point.metrics()));
            mqttClient.publish(topic, new MqttMessage(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to publish MQTT telemetry", exception);
        }
    }

    @PreDestroy
    void stop() throws Exception {
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
        if (client != null) {
            client.close();
        }
    }

    private MqttClient client() throws Exception {
        if (client != null && client.isConnected()) {
            return client;
        }
        client = new MqttClient(brokerUrl, "voltcanary-simulator-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        client.connect(options);
        return client;
    }
}
