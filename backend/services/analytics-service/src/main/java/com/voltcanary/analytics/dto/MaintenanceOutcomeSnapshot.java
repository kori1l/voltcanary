package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.UUID;

public record MaintenanceOutcomeSnapshot(
        UUID id,
        UUID accountId,
        UUID deviceId,
        UUID operationId,
        UUID maintenanceWindowId,
        Instant observedAt,
        Instant resolvedAt,
        String outcomeType,
        String failureMode,
        String component,
        String severity,
        Integer downtimeMinutes,
        String technicianNotes,
        String source,
        double confidence,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
