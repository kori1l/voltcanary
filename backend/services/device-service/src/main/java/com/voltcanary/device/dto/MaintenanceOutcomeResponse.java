package com.voltcanary.device.dto;

import com.voltcanary.device.domain.MaintenanceOutcomeSeverity;
import com.voltcanary.device.domain.MaintenanceOutcomeSource;
import com.voltcanary.device.domain.MaintenanceOutcomeType;
import java.time.Instant;
import java.util.UUID;

public record MaintenanceOutcomeResponse(
        UUID id,
        UUID accountId,
        UUID deviceId,
        UUID operationId,
        UUID maintenanceWindowId,
        Instant observedAt,
        Instant resolvedAt,
        MaintenanceOutcomeType outcomeType,
        String failureMode,
        String component,
        MaintenanceOutcomeSeverity severity,
        Integer downtimeMinutes,
        String technicianNotes,
        MaintenanceOutcomeSource source,
        double confidence,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
