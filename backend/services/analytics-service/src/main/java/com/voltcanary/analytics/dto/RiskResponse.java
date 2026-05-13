package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskResponse(
        UUID deviceId,
        double score,
        String level,
        List<String> explanations,
        Instant calculatedAt) {
}
