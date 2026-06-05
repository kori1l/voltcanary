package com.voltcanary.common.events.analytics;

import java.time.Instant;
import java.util.UUID;

public record AnomalyDetectedEvent(
        UUID eventId,
        UUID deviceId,
        UUID anomalyId,
        String ruleId,
        Instant windowStart,
        Instant windowEnd,
        String inputHash,
        String type,
        String severity,
        double score,
        String description,
        Instant detectedAt) {

    public AnomalyDetectedEvent(
            UUID eventId,
            UUID deviceId,
            UUID anomalyId,
            String type,
            String severity,
            double score,
            String description,
            Instant detectedAt) {
        this(eventId, deviceId, anomalyId, null, null, null, null, type, severity, score, description, detectedAt);
    }
}
