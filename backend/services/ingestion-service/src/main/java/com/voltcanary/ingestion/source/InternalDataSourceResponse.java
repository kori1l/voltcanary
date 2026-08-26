package com.voltcanary.ingestion.source;

import java.util.UUID;

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
