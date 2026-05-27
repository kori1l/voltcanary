package com.voltcanary.device.analyticsconfig;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record DeviceAnalyticsConfigRequest(
        @NotNull Boolean enabled,
        Map<String, Object> config) {
}
