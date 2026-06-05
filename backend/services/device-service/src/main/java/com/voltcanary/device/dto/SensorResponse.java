package com.voltcanary.device.dto;

import com.voltcanary.device.domain.Device;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;


public record SensorResponse(
        UUID id,
        UUID deviceId,
        String name,
        String type,
        String unit,
        String metricMapping,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt) {
}
