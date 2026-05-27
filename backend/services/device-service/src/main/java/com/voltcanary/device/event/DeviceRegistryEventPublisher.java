package com.voltcanary.device.event;

import com.voltcanary.common.events.device.DeviceRegistryChangedEvent;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class DeviceRegistryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistryEventPublisher.class);
    private static final String TOPIC = "device-registry.changed.v1";
    private static final String VERSION = "v1";

    private final KafkaTemplate<String, DeviceRegistryChangedEvent> kafkaTemplate;
    private final boolean publishEnabled;

    public DeviceRegistryEventPublisher(
            KafkaTemplate<String, DeviceRegistryChangedEvent> kafkaTemplate,
            @Value("${app.kafka.publish-enabled:true}") boolean publishEnabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.publishEnabled = publishEnabled;
    }

    public void publish(
            String resourceType,
            String action,
            UUID accountId,
            UUID deviceId,
            UUID resourceId,
            String status) {
        if (!publishEnabled) {
            return;
        }
        DeviceRegistryChangedEvent event = new DeviceRegistryChangedEvent(
                UUID.randomUUID(),
                VERSION,
                resourceType,
                action,
                accountId,
                deviceId,
                resourceId,
                status,
                Instant.now());
        String key = accountId == null ? resourceId.toString() : accountId.toString();
        log.info(
                "Publishing {} resourceType={} action={} accountId={} deviceId={} resourceId={}",
                TOPIC,
                resourceType,
                action,
                accountId,
                deviceId,
                resourceId);
        kafkaTemplate.send(TOPIC, key, event);
    }
}
