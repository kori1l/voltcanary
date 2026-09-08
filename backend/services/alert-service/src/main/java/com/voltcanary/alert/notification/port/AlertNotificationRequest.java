package com.voltcanary.alert.notification.port;

import com.voltcanary.alert.domain.Alert;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AlertNotificationRequest(
        UUID alertId,
        UUID alertEventId,
        UUID accountId,
        UUID deviceId,
        UUID sourceEventId,
        UUID anomalyId,
        UUID riskScoreId,
        String ruleId,
        Instant calculationWindowStart,
        Instant calculationWindowEnd,
        String calculationInputHash,
        String eventType,
        String alertType,
        String severity,
        String status,
        String title,
        String description,
        Instant occurredAt,
        String idempotencyKey) {

    public static AlertNotificationRequest fromAlert(
            Alert alert,
            UUID alertEventId,
            String eventType,
            Instant occurredAt) {
        Objects.requireNonNull(alert, "alert is required");
        String normalizedEventType = normalizeEventType(eventType);
        String eventKey = alertEventId == null ? alert.getId().toString() : alertEventId.toString();
        return new AlertNotificationRequest(
                alert.getId(),
                alertEventId,
                alert.getAccountId(),
                alert.getDeviceId(),
                alert.getSourceEventId(),
                alert.getAnomalyId(),
                alert.getRiskScoreId(),
                alert.getRuleId(),
                alert.getCalculationWindowStart(),
                alert.getCalculationWindowEnd(),
                alert.getCalculationInputHash(),
                normalizedEventType,
                alert.getAlertType(),
                alert.getSeverity().name(),
                alert.getStatus().name(),
                alert.getTitle(),
                alert.getDescription(),
                occurredAt == null ? alert.getCreatedAt() : occurredAt,
                eventKey + ":" + normalizedEventType);
    }

    public static AlertNotificationRequest fromDeliveryAttempt(
            Alert alert,
            String deliveryEventType,
            String idempotencyKey) {
        AlertNotificationRequest request = fromAlert(alert, null, deliveryEventType, alert.getCreatedAt());
        return new AlertNotificationRequest(
                request.alertId(),
                request.alertEventId(),
                request.accountId(),
                request.deviceId(),
                request.sourceEventId(),
                request.anomalyId(),
                request.riskScoreId(),
                request.ruleId(),
                request.calculationWindowStart(),
                request.calculationWindowEnd(),
                request.calculationInputHash(),
                request.eventType(),
                request.alertType(),
                request.severity(),
                request.status(),
                request.title(),
                request.description(),
                request.occurredAt(),
                idempotencyKey == null || idempotencyKey.isBlank() ? request.idempotencyKey() : idempotencyKey);
    }

    public static AlertNotificationRequest fromTestDelivery(
            UUID accountId,
            UUID channelId,
            Map<String, Object> sample,
            String idempotencyKey,
            Instant occurredAt) {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(channelId, "channelId is required");
        String title = sampleString(sample, "title", "VoltCanary notification test");
        return new AlertNotificationRequest(
                null,
                null,
                accountId,
                deterministicDeviceId(channelId),
                null,
                null,
                null,
                sampleString(sample, "ruleId", "notification-test"),
                null,
                null,
                null,
                "NOTIFICATION_TEST",
                sampleString(sample, "alertType", "NOTIFICATION_TEST"),
                sampleString(sample, "severity", "INFO"),
                "TEST",
                title,
                sampleString(sample, "description", "Operator-triggered notification delivery test."),
                occurredAt == null ? Instant.now() : occurredAt,
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? "notification-test:" + channelId
                        : idempotencyKey);
    }

    private static String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        return eventType.trim().toUpperCase(Locale.ROOT);
    }

    private static UUID deterministicDeviceId(UUID channelId) {
        return UUID.nameUUIDFromBytes(("notification-test:" + channelId).getBytes(StandardCharsets.UTF_8));
    }

    private static String sampleString(Map<String, Object> sample, String key, String defaultValue) {
        if (sample == null) {
            return defaultValue;
        }
        Object value = sample.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return text.length() > 255 ? text.substring(0, 255) : text;
    }
}
