package com.voltcanary.device.datasource;

import java.util.UUID;

public record InternalDataSourceResponse(
        UUID accountId,
        UUID deviceId,
        UUID dataSourceId,
        String externalId,
        String sourceType,
        String protocol,
        String status,
        String tokenHash) {
}
