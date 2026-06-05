package com.voltcanary.alert.dto;

import java.time.Instant;
import java.util.Map;

public record AlertSummaryResponse(
        long total,
        Map<String, Long> byStatus,
        Map<String, Long> bySeverity,
        Map<String, Long> byDevice,
        Instant generatedAt) {
}
