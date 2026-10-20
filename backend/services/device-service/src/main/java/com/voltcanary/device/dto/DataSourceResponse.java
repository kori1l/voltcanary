package com.voltcanary.device.dto;

import java.util.Map;
import java.util.UUID;

public record DataSourceResponse(UUID id, UUID deviceId, String sourceType, String protocol, String externalId,
        Map<String,Object> mapping, Map<String,Object> adapterConfig, Map<String,String> credentialReferences, String homeRegion) {
    public DataSourceResponse(UUID id, UUID deviceId, String sourceType, String protocol, String externalId) {
        this(id, deviceId, sourceType, protocol, externalId, Map.of(), Map.of(), Map.of(), null);
    }
}
