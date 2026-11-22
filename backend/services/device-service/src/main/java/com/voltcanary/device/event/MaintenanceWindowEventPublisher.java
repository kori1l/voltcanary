package com.voltcanary.device.event;

import com.voltcanary.common.events.device.MaintenanceWindowChangedEvent;
import com.voltcanary.device.config.DeviceKafkaProperties;
import com.voltcanary.device.domain.MaintenanceWindow;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MaintenanceWindowEventPublisher {

    private static final String TOPIC = "maintenance-window.changed.v1";

    private final DeviceOutboxService outboxService;
    private final boolean publishEnabled;

    public MaintenanceWindowEventPublisher(
        DeviceOutboxService outboxService,
        DeviceKafkaProperties kafkaProperties
    ) {
        this.outboxService = outboxService;
        this.publishEnabled = kafkaProperties.isPublishEnabled();
    }

    public void publish(String eventType, MaintenanceWindow window, UUID actorUserId) {
        if (!publishEnabled) {
            return;
        }
        Instant occurredAt = Instant.now();
        MaintenanceWindowChangedEvent event = new MaintenanceWindowChangedEvent(
            UUID.randomUUID(),
            "v1",
            eventType,
            window.getId(),
            window.getAccountId(),
            window.getDeviceId(),
            window.getTitle(),
            window.getDescription(),
            window.getStartAt(),
            window.getEndAt(),
            window.isSuppressAlerts(),
            window.isExcludeFromAnalytics(),
            window.isExcludeFromMl(),
            window.getCancelledAt(),
            window.getCancellationReason(),
            window.getVersion(),
            null,
            "device-service",
            actorUserId,
            occurredAt
        );
        log.info(
            "Publishing {} eventType={} maintenanceWindowId={} deviceId={} version={}",
            TOPIC,
            eventType,
            window.getId(),
            window.getDeviceId(),
            window.getVersion()
        );
        outboxService.enqueue(
            "maintenance-window:" + window.getId() + ":" + window.getVersion(),
            TOPIC,
            window.getDeviceId().toString(),
            event
        );
    }
}
