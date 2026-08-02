package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlLabelType;
import java.time.Instant;
import java.util.UUID;

public record MlCanonicalMetricRowDto(
        UUID accountId,
        UUID datasetVersionId,
        UUID deviceId,
        String externalDeviceKey,
        String virtualDeviceKey,
        String deviceType,
        String sourceId,
        String sensorId,
        Instant timestamp,
        String metricName,
        String canonicalMetricName,
        double numericValue,
        String rawUnit,
        String canonicalUnit,
        String qualityFlags,
        String metadataJson,
        String sourceLineage,
        MlLabelType label,
        Instant createdAt) {
}
