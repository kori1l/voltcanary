package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.UUID;

public record RecommendationResponse(
        UUID id,
        UUID deviceId,
        String title,
        String description,
        String priority,
        Instant createdAt) {
}
