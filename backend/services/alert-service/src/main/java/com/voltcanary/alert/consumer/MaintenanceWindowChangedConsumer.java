package com.voltcanary.alert.consumer;

import com.voltcanary.alert.service.MaintenanceWindowProjectionService;
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

    private final MaintenanceWindowProjectionService projectionService;

    @KafkaListener(
        topics = "maintenance-window.changed.v1",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "maintenanceWindowChangedKafkaListenerContainerFactory"
    )
    public void consume(MaintenanceWindowChangedEvent event) {
        boolean projected = projectionService.project(event);
        log.info(
            "maintenanceWindowChangedConsumed eventId={} maintenanceWindowId={} deviceId={} projected={}",
            event.eventId(),
            event.maintenanceWindowId(),
            event.deviceId(),
            projected
        );
    }
}
