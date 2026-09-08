package com.voltcanary.alert.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationAttemptResponse(
        UUID id,
        UUID alertId,
        UUID channelId,
        UUID accountId,
        String channelType,
        String deliveryEventType,
        String status,
        int retryCount,
        int maxAttempts,
        Instant nextAttemptAt,
        boolean retryExhausted,
        String lastErrorCode,
        String providerResponseSummary,
        String providerMessageId,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt) {
}
