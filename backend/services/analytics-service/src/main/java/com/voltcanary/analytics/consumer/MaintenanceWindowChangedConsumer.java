package com.voltcanary.analytics.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.producer.AnalyticsDeadLetterPublisher;
import com.voltcanary.analytics.service.MaintenanceWindowProjectionService;
import com.voltcanary.common.events.device.MaintenanceWindowChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
public class MaintenanceWindowChangedConsumer {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowChangedConsumer.class);
    private static final String SOURCE_TOPIC = "maintenance-window.changed.v1";

    private final ObjectMapper objectMapper;
    private final MaintenanceWindowProjectionService projectionService;
    private final AnalyticsDeadLetterPublisher deadLetterPublisher;

    public MaintenanceWindowChangedConsumer(
            ObjectMapper objectMapper,
            MaintenanceWindowProjectionService projectionService,
            AnalyticsDeadLetterPublisher deadLetterPublisher) {
        this.objectMapper = objectMapper;
        this.projectionService = projectionService;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @KafkaListener(topics = "maintenance-window.changed.v1", groupId = "${spring.kafka.consumer.group-id}-maintenance")
    public void consume(String eventPayload) {
        MaintenanceWindowChangedEvent event = readEvent(eventPayload);
        if (event.deviceId() == null || event.maintenanceWindowId() == null) {
            deadLetterPublisher.publish(SOURCE_TOPIC, eventPayload, "MaintenanceWindowChangedEvent ids are required");
            throw new IllegalArgumentException("MaintenanceWindowChangedEvent ids are required");
        }
        boolean processed = projectionService.project(event);
        log.info(
                "analyticsMaintenanceWindowChangedReceived eventId={} maintenanceWindowId={} deviceId={} version={} processed={}",
                event.eventId(),
                event.maintenanceWindowId(),
                event.deviceId(),
                event.version(),
                processed);
    }

    private MaintenanceWindowChangedEvent readEvent(String eventPayload) {
        try {
            return objectMapper.readValue(eventPayload, MaintenanceWindowChangedEvent.class);
        } catch (JsonProcessingException exception) {
            deadLetterPublisher.publish(SOURCE_TOPIC, eventPayload, "Invalid MaintenanceWindowChangedEvent payload");
            throw new IllegalArgumentException("Invalid MaintenanceWindowChangedEvent payload", exception);
        }
    }
}
