package com.voltcanary.alert.domain;

public enum NotificationAttemptStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    RETRYING,
    DISABLED
}
