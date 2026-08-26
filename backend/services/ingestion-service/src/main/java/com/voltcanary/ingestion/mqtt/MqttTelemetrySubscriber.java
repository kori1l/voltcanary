package com.voltcanary.ingestion.mqtt;

import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.pipeline.TelemetryIngestionPipeline;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.ingestion.mqtt", name = "enabled", havingValue = "true")
public class MqttTelemetrySubscriber implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttTelemetrySubscriber.class);

    private final MqttTopicParser topicParser;
    private final MqttPayloadMapper payloadMapper;
    private final TelemetryIngestionPipeline pipeline;
    private final String brokerUrl;
    private final String clientId;
    private final String topicFilter;
    private MqttClient client;

    public MqttTelemetrySubscriber(
            MqttTopicParser topicParser,
            MqttPayloadMapper payloadMapper,
            TelemetryIngestionPipeline pipeline,
            IngestionProperties properties) {
        this.topicParser = topicParser;
        this.payloadMapper = payloadMapper;
        this.pipeline = pipeline;
        this.brokerUrl = properties.getMqtt().getBrokerUrl();
        this.clientId = properties.getMqtt().getClientId();
        this.topicFilter = properties.getMqtt().getTopicFilter();
    }

    @PostConstruct
    void start() throws Exception {
        client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        client.setCallback(this);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        client.connect(options);
        client.subscribe(topicFilter);
        log.info("MQTT telemetry subscriber connected brokerUrl={} topicFilter={}", brokerUrl, topicFilter);
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

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT telemetry subscriber connection lost", cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        topicParser.sourceId(topic).ifPresentOrElse(
                sourceId -> pipeline.ingest(payloadMapper.map(sourceId, topic, message.getPayload())),
                () -> log.warn("Ignoring MQTT message with unsupported topic={}", topic));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }
}
