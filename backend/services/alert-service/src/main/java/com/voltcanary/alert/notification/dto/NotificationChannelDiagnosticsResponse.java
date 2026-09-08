package com.voltcanary.alert.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationChannelDiagnosticsResponse(
        UUID channelId,
        UUID accountId,
        String channelType,
        boolean enabled,
        boolean adapterRegistered,
        Instant lastSuccessfulDeliveryAt,
        Instant lastFailureAt,
        String lastFailureErrorCode,
        String lastFailureSummary,
        long retryingCount,
        long failedCount,
        long pendingCount,
        Long oldestPendingOrRetryingAgeSeconds,
        UUID lastTestAttemptId,
        String lastTestAttemptStatus,
        int lastTestRetryCount,
        int lastTestMaxAttempts,
        Instant lastTestNextAttemptAt,
        boolean lastTestRetryExhausted) {
}
