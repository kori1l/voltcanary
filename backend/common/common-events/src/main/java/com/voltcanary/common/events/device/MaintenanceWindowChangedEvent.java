package com.voltcanary.common.events.device;

import java.time.Instant;
import java.util.UUID;

public record MaintenanceWindowChangedEvent(
        UUID eventId,
        String eventVersion,
        String eventType,
        UUID maintenanceWindowId,
        UUID accountId,
        UUID deviceId,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        boolean suppressAlerts,
        boolean excludeFromAnalytics,
        boolean excludeFromMl,
        Instant cancelledAt,
        String cancellationReason,
        long version,
        String correlationId,
        String producer,
        UUID actorUserId,
        Instant occurredAt) {

    public MaintenanceWindowChangedEvent(
            UUID eventId,
            String eventType,
            UUID maintenanceWindowId,
            UUID accountId,
            UUID deviceId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            boolean suppressAlerts,
            boolean excludeFromAnalytics,
            boolean excludeFromMl,
            Instant cancelledAt,
            String cancellationReason,
            long version,
            UUID actorUserId,
            Instant occurredAt) {
        this(
                eventId,
                "v1",
                eventType,
                maintenanceWindowId,
                accountId,
                deviceId,
                title,
                description,
                startAt,
                endAt,
                suppressAlerts,
                excludeFromAnalytics,
                excludeFromMl,
                cancelledAt,
                cancellationReason,
                version,
                null,
                "device-service",
                actorUserId,
                occurredAt);
    }
}
