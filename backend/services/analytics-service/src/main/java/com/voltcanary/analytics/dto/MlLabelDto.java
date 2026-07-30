package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlLabelSource;
import com.voltcanary.analytics.domain.MlLabelType;
import java.time.Instant;
import java.util.UUID;

public record MlLabelDto(
        UUID id,
        UUID accountId,
        UUID datasetVersionId,
        UUID deviceId,
        String externalDeviceKey,
        String metricName,
        Instant labelStartAt,
        Instant labelEndAt,
        MlLabelType label,
        MlLabelSource source,
        UUID createdBy,
        Instant createdAt,
        String reason) {
}
