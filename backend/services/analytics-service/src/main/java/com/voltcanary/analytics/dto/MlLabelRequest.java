package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlLabelType;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record MlLabelRequest(
        UUID datasetVersionId,
        UUID deviceId,
        String externalDeviceKey,
        String metricName,
        Instant labelStartAt,
        Instant labelEndAt,
        @NotNull MlLabelType label,
        String reason) {
}
