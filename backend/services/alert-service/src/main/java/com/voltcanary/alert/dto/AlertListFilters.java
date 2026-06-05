package com.voltcanary.alert.dto;

import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import java.time.Instant;
import java.util.UUID;

public record AlertListFilters(
        UUID deviceId,
        AlertStatus status,
        AlertSeverity severity,
        String alertType,
        Instant from,
        Instant to,
        Integer limit) {

    public int normalizedLimit() {
        if (limit == null) {
            return 100;
        }
        return Math.max(1, Math.min(limit, 500));
    }
}
