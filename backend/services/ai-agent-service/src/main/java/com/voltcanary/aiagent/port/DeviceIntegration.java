package com.voltcanary.aiagent.port;

import java.util.Map;

/** Reviewed source provisioning; implementations own fixed provider-specific destinations. */
public interface DeviceIntegration {
    String provider();
    void prepare(Map<String, Object> intent, String authorization, String creationKey);
    Map<String, Object> connect(
        String deviceId,
        Map<String, Object> intent,
        String authorization,
        String creationKey
    );
    Map<?, ?> status(String deviceId, String authorization);
}
