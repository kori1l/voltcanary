package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.UUID;

public record MaintenanceWindowSnapshot(
        UUID id,
        UUID accountId,
        UUID deviceId,
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
