package com.voltcanary.ingestion.mqtt;

import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.adapter.mqtt.MqttAdapterRequest;
import com.voltcanary.ingestion.adapter.mqtt.MqttPayloadMappingException;
import com.voltcanary.ingestion.adapter.mqtt.UnsupportedMqttTopicException;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.ingress.IngressOrchestrator;
import io.micrometer.core.instrument.MeterRegistry;
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
public class MqttIngress implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttIngress.class);
    private static final String ADAPTER_ID = "generic-mqtt";

    private final IngressOrchestrator ingressOrchestrator;
    private final MeterRegistry meterRegistry;
    private final String brokerUrl;
    private final String clientId;
    private final String topicFilter;
    private MqttClient client;

    public MqttIngress(
            IngressOrchestrator ingressOrchestrator,
            MeterRegistry meterRegistry,
            IngestionProperties properties) {
        this.ingressOrchestrator = ingressOrchestrator;
        this.meterRegistry = meterRegistry;
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
        log.info("MQTT ingress connected brokerUrl={} topicFilter={}", brokerUrl, topicFilter);
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
        record("broker_reconnect");
        log.warn("MQTT ingress connection lost brokerUrl={} topicFilter={}", brokerUrl, topicFilter, cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            ingressOrchestrator.dispatch(
                    AdapterType.GENERIC_MQTT,
                    ProtocolType.MQTT,
                    TransportType.MQTT,
                    MqttAdapterRequest.class,
                    new MqttAdapterRequest(topic, message.getPayload()),
                    null);
            record("accepted");
        } catch (UnsupportedMqttTopicException exception) {
            record("unsupported_topic");
            log.warn("Ignoring MQTT message with unsupported topic={}", topic);
        } catch (MqttPayloadMappingException exception) {
            record("invalid_payload");
            log.warn("Rejecting MQTT message with invalid payload topic={}", topic, exception);
        } catch (RuntimeException exception) {
            record("pipeline_rejection");
            log.warn("Rejecting MQTT message after adapter mapping topic={}", topic, exception);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    private void record(String outcome) {
        meterRegistry.counter(
                "voltcanary.ingestion.mqtt.messages",
                "adapter",
                ADAPTER_ID,
                "outcome",
                outcome).increment();
    }
}
