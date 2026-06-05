package com.voltcanary.device.dto;

import java.util.UUID;import com.voltcanary.device.domain.Device;


public record DataSourceResponse(UUID id, UUID deviceId, String sourceType, String protocol, String externalId) {
}
