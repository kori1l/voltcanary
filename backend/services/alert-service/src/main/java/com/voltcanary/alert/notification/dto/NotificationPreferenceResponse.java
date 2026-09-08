package com.voltcanary.alert.notification.dto;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

public record NotificationPreferenceResponse(
        UUID id,
        UUID accountId,
        UUID userId,
        boolean enabled,
        Set<String> severities,
        Set<String> channelTypes,
        Set<UUID> deviceIds,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        String timezone,
        boolean criticalBypassQuietHours,
        Instant createdAt,
        Instant updatedAt) {
}
