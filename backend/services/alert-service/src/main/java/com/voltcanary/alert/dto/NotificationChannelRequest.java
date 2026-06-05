package com.voltcanary.alert.dto;

import java.util.Map;

public record NotificationChannelRequest(
        String name,
        String channelType,
        Map<String, Object> config,
        String credentialsRef,
        Boolean enabled) {
}
