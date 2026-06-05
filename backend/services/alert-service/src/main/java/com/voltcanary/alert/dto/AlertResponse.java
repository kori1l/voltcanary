package com.voltcanary.alert.dto;

import java.time.Instant;
import java.util.UUID;

public record AlertResponse(
        UUID id,
        UUID deviceId,
        UUID accountId,
        UUID sourceEventId,
        UUID anomalyId,
        UUID riskScoreId,
        String ruleId,
        Instant calculationWindowStart,
        Instant calculationWindowEnd,
        String calculationInputHash,
        String alertType,
        String title,
        String description,
        String severity,
        String status,
        Instant createdAt,
        Instant acknowledgedAt,
        UUID acknowledgedBy,
        Instant resolvedAt,
        UUID resolvedBy,
        UUID assignedTo,
        String resolutionReason) {
}
