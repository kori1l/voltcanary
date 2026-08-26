package com.voltcanary.ingestion.connector;

import java.util.UUID;

public record ConnectorLease(UUID dataSourceId, String ownerId, long fencingToken) {

    public ConnectorLease {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("dataSourceId must not be null");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }
}
