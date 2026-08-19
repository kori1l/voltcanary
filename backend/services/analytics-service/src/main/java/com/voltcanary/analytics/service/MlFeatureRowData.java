package com.voltcanary.analytics.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MlFeatureRowData(
        UUID deviceId,
        String virtualDeviceKey,
        String deviceType,
        Instant predictionAt,
        Integer horizonDays,
        String entityId,
        Map<String, Object> featureValues,
        Map<String, Object> labelValues,
        String splitAssignment,
        Map<String, Object> sourceLineage,
        List<String> qualityFlags) {
}
