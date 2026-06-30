package com.voltcanary.device.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MaintenanceOperationRequest(
        @NotNull @Size(max = 255) String title,
        @Size(max = 5000) String description,
        @NotEmpty List<@NotNull UUID> deviceIds,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        Boolean suppressAlerts,
        Boolean excludeFromAnalytics,
        Boolean excludeFromMl) {
}
