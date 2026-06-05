package com.voltcanary.alert.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationChannelResponse(
        UUID id,
        UUID accountId,
        String name,
        String channelType,
        Map<String, Object> config,
        String credentialsRef,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}
