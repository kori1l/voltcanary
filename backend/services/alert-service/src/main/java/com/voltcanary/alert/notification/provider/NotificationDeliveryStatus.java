package com.voltcanary.alert.notification.provider;

public enum NotificationDeliveryStatus {
    SENT,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    DISABLED
}
