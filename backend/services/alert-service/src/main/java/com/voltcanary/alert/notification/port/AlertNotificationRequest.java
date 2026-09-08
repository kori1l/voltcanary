package com.voltcanary.alert.notification.port;

import com.voltcanary.alert.domain.Alert;
import java.time.Instant;
import java.util.Locale;
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

    private static String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        return eventType.trim().toUpperCase(Locale.ROOT);
    }
}
