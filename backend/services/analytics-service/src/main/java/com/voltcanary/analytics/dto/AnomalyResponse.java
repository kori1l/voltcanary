package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.UUID;

public record AnomalyResponse(
        UUID id,
        UUID deviceId,
        String type,
        String severity,
        double score,
        String description,
        Instant detectedAt) {
}
