package com.voltcanary.device.dto;

import java.util.UUID;import com.voltcanary.device.domain.Device;


public record InternalDataSourceResponse(
        UUID accountId,
        UUID deviceId,
        UUID dataSourceId,
        String externalId,
        String sourceType,
        String protocol,
        String status,
        String tokenPrefix,
        String tokenHash) {
}
