package com.voltcanary.analytics.ml;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MlInferenceRequest(
        UUID deviceId,
        Instant timestamp,
        Map<String, Object> metrics,
        List<MlExcludedInterval> excludedIntervals,
        Map<String, Object> baseline,
        Map<String, Object> recentWindow,
        Map<String, Object> modelPreference) {

    public MlInferenceRequest(UUID deviceId, Instant timestamp, Map<String, Object> metrics) {
        this(deviceId, timestamp, metrics, List.of());
    }

    public MlInferenceRequest(
            UUID deviceId,
            Instant timestamp,
            Map<String, Object> metrics,
            List<MlExcludedInterval> excludedIntervals) {
        this(deviceId, timestamp, metrics, excludedIntervals, null, null, null);
    }
}
