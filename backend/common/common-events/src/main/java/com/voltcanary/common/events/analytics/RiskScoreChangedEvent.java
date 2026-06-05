package com.voltcanary.common.events.analytics;

import java.time.Instant;
import java.util.UUID;

public record RiskScoreChangedEvent(
        UUID eventId,
        UUID deviceId,
        UUID riskScoreId,
        Instant windowStart,
        Instant windowEnd,
        String inputHash,
        double previousScore,
        double currentScore,
        String riskLevel,
        Instant changedAt) {

    public RiskScoreChangedEvent(
            UUID eventId,
            UUID deviceId,
            double previousScore,
            double currentScore,
            String riskLevel,
            Instant changedAt) {
        this(eventId, deviceId, null, null, null, null, previousScore, currentScore, riskLevel, changedAt);
    }
}
