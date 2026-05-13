package com.voltcanary.common.events.analytics;

import java.time.Instant;
import java.util.UUID;

public record AnomalyDetectedEvent(
        UUID eventId,
        UUID deviceId,
        UUID anomalyId,
        String severity,
        double score,
        String description,
        Instant detectedAt) {
}
