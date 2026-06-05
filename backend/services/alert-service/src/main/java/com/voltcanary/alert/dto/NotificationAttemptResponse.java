package com.voltcanary.alert.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationAttemptResponse(
        UUID id,
        UUID alertId,
        UUID channelId,
        UUID accountId,
        String channelType,
        String status,
        int retryCount,
        String lastErrorCode,
        String providerResponseSummary,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt) {
}
