package com.voltcanary.device.analyticsconfig;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DeviceAnalyticsConfigResponse(
        UUID deviceId,
        boolean enabled,
        Map<String, Object> config,
        Instant updatedAt) {
}
