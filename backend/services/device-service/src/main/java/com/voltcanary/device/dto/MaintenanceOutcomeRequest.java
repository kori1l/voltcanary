package com.voltcanary.device.dto;

import com.voltcanary.device.domain.MaintenanceOutcomeSeverity;
import com.voltcanary.device.domain.MaintenanceOutcomeSource;
import com.voltcanary.device.domain.MaintenanceOutcomeType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record MaintenanceOutcomeRequest(
        @NotNull UUID deviceId,
        UUID maintenanceWindowId,
        @NotNull Instant observedAt,
        Instant resolvedAt,
        @NotNull MaintenanceOutcomeType outcomeType,
        @Size(max = 255) String failureMode,
        @Size(max = 255) String component,
        MaintenanceOutcomeSeverity severity,
        @Min(0) Integer downtimeMinutes,
        @Size(max = 5000) String technicianNotes,
        MaintenanceOutcomeSource source,
        @DecimalMin("0.0") @DecimalMax("1.0") Double confidence) {
}
