package com.voltcanary.alert.notification.provider;

public record NotificationDeliveryResult(
        NotificationDeliveryStatus status,
        String providerResponseSummary,
        String providerMessageId,
        String errorCode,
        boolean retryable) {

    public static NotificationDeliveryResult sent(String providerResponseSummary, String providerMessageId) {
        return new NotificationDeliveryResult(
                NotificationDeliveryStatus.SENT,
                providerResponseSummary,
                providerMessageId,
                null,
                false);
    }

    public static NotificationDeliveryResult retryableFailure(String errorCode, String providerResponseSummary) {
        return new NotificationDeliveryResult(
                NotificationDeliveryStatus.RETRYABLE_FAILURE,
                providerResponseSummary,
                null,
                errorCode,
                true);
    }

    public static NotificationDeliveryResult permanentFailure(String errorCode, String providerResponseSummary) {
        return new NotificationDeliveryResult(
                NotificationDeliveryStatus.PERMANENT_FAILURE,
                providerResponseSummary,
                null,
                errorCode,
                false);
    }

    public static NotificationDeliveryResult disabled(String providerResponseSummary) {
        return new NotificationDeliveryResult(
                NotificationDeliveryStatus.DISABLED,
                providerResponseSummary,
                null,
                null,
                false);
    }
}
