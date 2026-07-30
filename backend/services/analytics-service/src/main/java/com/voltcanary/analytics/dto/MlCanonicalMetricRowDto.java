package com.voltcanary.analytics.dto;

import com.voltcanary.analytics.domain.MlLabelType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MlCanonicalMetricRowDto(
        UUID accountId,
        UUID deviceId,
        String externalDeviceKey,
        Instant timestamp,
        String metricName,
        double numericValue,
        String unit,
        MlLabelType label,
        Map<String, String> metadata) {
}
