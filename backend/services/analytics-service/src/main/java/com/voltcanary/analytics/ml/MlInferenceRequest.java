package com.voltcanary.analytics.ml;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MlInferenceRequest(
        UUID deviceId,
        Instant timestamp,
        Map<String, Object> metrics) {
}
