package com.voltcanary.device.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record MaintenanceWindowRequest(
        @NotNull @Size(max = 255) String title,
        @Size(max = 5000) String description,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        Boolean suppressAlerts,
        Boolean excludeFromAnalytics,
        Boolean excludeFromMl) {
}
