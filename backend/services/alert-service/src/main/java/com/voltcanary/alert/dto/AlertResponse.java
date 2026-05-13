package com.voltcanary.alert.dto;

import java.time.Instant;
import java.util.UUID;

public record AlertResponse(
        UUID id,
        UUID deviceId,
        String alertType,
        String title,
        String description,
        String severity,
        String status,
        Instant createdAt,
        Instant acknowledgedAt,
        Instant resolvedAt) {
}
