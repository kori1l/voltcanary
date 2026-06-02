package com.voltcanary.analytics.dto;

import java.time.Instant;
import java.util.UUID;

public record AnalyticsJobResponse(
        UUID id,
        UUID deviceId,
        String jobType,
        String status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage) {
}
