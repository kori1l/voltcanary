package com.voltcanary.alert.notification.provider;

import com.voltcanary.alert.notification.port.AlertNotificationRequest;
import java.util.Map;
import java.util.UUID;

public record NotificationDeliveryCommand(
        UUID attemptId,
        UUID channelId,
        String channelType,
        String idempotencyKey,
        String deliveryEventType,
        Map<String, Object> channelConfig,
        String credentialsRef,
        AlertNotificationRequest alert) {
}
