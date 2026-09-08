package com.voltcanary.alert.notification.port;

public interface AlertNotificationPort {

    void alertCreated(AlertNotificationRequest request);

    void alertEscalated(AlertNotificationRequest request);
}
