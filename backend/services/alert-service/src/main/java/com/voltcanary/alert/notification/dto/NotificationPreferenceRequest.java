package com.voltcanary.alert.notification.dto;

import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

public record NotificationPreferenceRequest(
        Boolean enabled,
        Set<String> severities,
        Set<String> channelTypes,
        Set<UUID> deviceIds,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        String timezone,
        Boolean criticalBypassQuietHours) {
}
