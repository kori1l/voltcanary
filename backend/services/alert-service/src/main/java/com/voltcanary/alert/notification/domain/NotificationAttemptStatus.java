package com.voltcanary.alert.notification.domain;

public enum NotificationAttemptStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    RETRYING,
    DISABLED
}
