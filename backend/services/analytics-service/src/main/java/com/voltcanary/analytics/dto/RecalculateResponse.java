package com.voltcanary.analytics.dto;

import java.util.UUID;

public record RecalculateResponse(
    String status,
    UUID deviceId,
    UUID baselineId,
    int anomalyCount,
    double riskScore,
    String riskLevel,
    int recommendationCount
) {
}
