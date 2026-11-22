package com.voltcanary.analytics.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.service.MaintenanceWindowProjectionService;
import com.voltcanary.common.events.device.MaintenanceWindowChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "consume-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class MaintenanceWindowChangedConsumer {

    private final ObjectMapper objectMapper;
    private final MaintenanceWindowProjectionService projectionService;

    @KafkaListener(
        topics = "maintenance-window.changed.v1",
        groupId = "${spring.kafka.consumer.group-id}-maintenance"
    )
    public void consume(String eventPayload) {
        MaintenanceWindowChangedEvent event = readEvent(eventPayload);
        if (event.deviceId() == null || event.maintenanceWindowId() == null) {
            throw new IllegalArgumentException("MaintenanceWindowChangedEvent ids are required");
        }
        boolean processed = projectionService.project(event);
        log.info(
            "analyticsMaintenanceWindowChangedReceived eventId={} maintenanceWindowId={} deviceId={} version={} processed={}",
            event.eventId(),
            event.maintenanceWindowId(),
            event.deviceId(),
            event.version(),
            processed
        );
    }

    private MaintenanceWindowChangedEvent readEvent(String eventPayload) {
        try {
            return objectMapper.readValue(eventPayload, MaintenanceWindowChangedEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid MaintenanceWindowChangedEvent payload",
                exception
            );
        }
    }
}
