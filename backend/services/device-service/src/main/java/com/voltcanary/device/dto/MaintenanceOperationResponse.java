package com.voltcanary.device.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MaintenanceOperationResponse(
        UUID id,
        UUID accountId,
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
        List<MaintenanceOperationDeviceResponse> devices,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
