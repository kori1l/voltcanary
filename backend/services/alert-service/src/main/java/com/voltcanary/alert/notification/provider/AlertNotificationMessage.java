package com.voltcanary.alert.notification.provider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record AlertNotificationMessage(
        String subject,
        String text,
        Map<String, Object> payload) {

    public static AlertNotificationMessage from(NotificationDeliveryCommand command) {
        String severity = valueOrUnknown(command.alert().severity());
        String title = valueOrUnknown(command.alert().title());
        String subject = command.alert().alertId() == null
                ? "Notification test: " + title
                : severity + " alert: " + title;
        StringBuilder text = new StringBuilder()
                .append(subject)
                .append('\n')
                .append("Type: ").append(valueOrUnknown(command.alert().alertType()))
                .append('\n')
                .append("Status: ").append(valueOrUnknown(command.alert().status()))
                .append('\n')
                .append("Device: ").append(command.alert().deviceId());
        if (command.alert().alertId() != null) {
            text.append('\n').append("Alert: ").append(command.alert().alertId());
        }
        if (command.alert().description() != null && !command.alert().description().isBlank()) {
            text.append('\n').append(command.alert().description().trim());
        }
        return new AlertNotificationMessage(subject, text.toString(), payload(command));
    }

    private static Map<String, Object> payload(NotificationDeliveryCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", command.deliveryEventType());
        payload.put("idempotencyKey", command.idempotencyKey());
        payload.put("channelType", command.channelType());
        payload.put("alertId", command.alert().alertId());
        payload.put("alertEventId", command.alert().alertEventId());
        payload.put("accountId", command.alert().accountId());
        payload.put("deviceId", command.alert().deviceId());
        payload.put("sourceEventId", command.alert().sourceEventId());
        payload.put("anomalyId", command.alert().anomalyId());
        payload.put("riskScoreId", command.alert().riskScoreId());
        payload.put("ruleId", command.alert().ruleId());
        payload.put("calculationWindowStart", command.alert().calculationWindowStart());
        payload.put("calculationWindowEnd", command.alert().calculationWindowEnd());
        payload.put("calculationInputHash", command.alert().calculationInputHash());
        payload.put("alertType", command.alert().alertType());
        payload.put("severity", command.alert().severity());
        payload.put("status", command.alert().status());
        payload.put("title", command.alert().title());
        payload.put("description", command.alert().description());
        payload.put("createdAt", command.alert().occurredAt());
        payload.put("channelId", command.channelId());
        payload.put("sentAt", Instant.now());
        return payload;
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim();
    }
}
