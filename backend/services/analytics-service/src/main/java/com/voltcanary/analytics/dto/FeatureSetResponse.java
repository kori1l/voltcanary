package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record FeatureSetResponse(
        UUID id,
        UUID deviceId,
        Instant windowStart,
        Instant windowEnd,
        Map<String, Object> features) {
}
