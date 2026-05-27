package com.voltcanary.device.device;

import java.util.Map;
import java.util.UUID;

public record DeviceResponse(
        UUID id,
        UUID locationId,
        String name,
        String type,
        String manufacturer,
        String model,
        String status,
        Map<String, Object> metadata) {
}
