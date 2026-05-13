package com.voltcanary.common.events.analytics;

import java.time.Instant;
import java.util.UUID;

public record RiskScoreChangedEvent(
        UUID eventId,
        UUID deviceId,
        double previousScore,
        double currentScore,
        String riskLevel,
        Instant changedAt) {
}
