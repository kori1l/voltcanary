package com.voltcanary.alert.notification.provider;

import java.util.Map;

public interface NotificationProviderAdapter {

    String channelType();

    default void validateConfig(Map<String, Object> config) {
    }

    NotificationDeliveryResult deliver(NotificationDeliveryCommand command);
}
