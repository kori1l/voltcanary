package com.voltcanary.device.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.domain.DeviceAnalyticsConfig;
import com.voltcanary.device.dto.DeviceAnalyticsConfigResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DeviceAnalyticsPolicyMapper {

    private final ObjectMapper objectMapper;

    public DeviceAnalyticsPolicyMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DeviceAnalyticsConfigResponse defaults(UUID deviceId) {
        return new DeviceAnalyticsConfigResponse(deviceId, true, Map.of(), null);
    }

    public DeviceAnalyticsConfigResponse toResponse(DeviceAnalyticsConfig entity) {
        return new DeviceAnalyticsConfigResponse(
                entity.getDeviceId(),
                entity.isEnabled(),
                fromJson(entity.getConfigJson()),
                entity.getUpdatedAt());
    }

    public String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid analytics config", exception);
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
