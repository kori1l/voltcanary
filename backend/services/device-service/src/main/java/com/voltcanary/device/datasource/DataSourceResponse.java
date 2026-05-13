package com.voltcanary.device.datasource;

import java.util.UUID;

public record DataSourceResponse(UUID id, UUID deviceId, String sourceType, String protocol, String externalId) {
}
