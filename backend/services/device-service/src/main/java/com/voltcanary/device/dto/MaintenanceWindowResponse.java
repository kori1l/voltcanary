package com.voltcanary.device.dto;

import java.time.Instant;
import java.util.UUID;

public record MaintenanceWindowResponse(
        UUID id,
        UUID accountId,
        UUID deviceId,
        UUID operationId,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        String status,
        boolean suppressAlerts,
        boolean excludeFromAnalytics,
        boolean excludeFromMl,
        Instant cancelledAt,
        String cancellationReason,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
