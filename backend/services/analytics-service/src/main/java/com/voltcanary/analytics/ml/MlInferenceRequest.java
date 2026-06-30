package com.voltcanary.analytics.ml;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MlInferenceRequest(
        UUID deviceId,
        Instant timestamp,
        Map<String, Object> metrics,
        List<MlExcludedInterval> excludedIntervals) {

    public MlInferenceRequest(UUID deviceId, Instant timestamp, Map<String, Object> metrics) {
        this(deviceId, timestamp, metrics, List.of());
    }
}
