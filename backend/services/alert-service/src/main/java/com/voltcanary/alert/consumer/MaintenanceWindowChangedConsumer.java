package com.voltcanary.alert.consumer;

import com.voltcanary.alert.service.MaintenanceWindowProjectionService;
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

    private final MaintenanceWindowProjectionService projectionService;

    public MaintenanceWindowChangedConsumer(MaintenanceWindowProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @KafkaListener(
            topics = "maintenance-window.changed.v1",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "maintenanceWindowChangedKafkaListenerContainerFactory")
    public void consume(MaintenanceWindowChangedEvent event) {
        boolean projected = projectionService.project(event);
        log.info(
                "maintenanceWindowChangedConsumed eventId={} maintenanceWindowId={} deviceId={} projected={}",
                event.eventId(),
                event.maintenanceWindowId(),
                event.deviceId(),
                projected);
    }
}
