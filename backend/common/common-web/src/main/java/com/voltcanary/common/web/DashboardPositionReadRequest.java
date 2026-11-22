package com.voltcanary.common.web;

import java.util.Map;
import java.util.UUID;

public record DashboardPositionReadRequest(
    DashboardOwnerReadRequest scope,
    UUID deviceId,
    String sourceId,
    Map<UUID, String> vehicleSources
) {
    public DashboardPositionReadRequest(
        DashboardOwnerReadRequest scope,
        UUID deviceId,
        String sourceId
    ) {
        this(scope, deviceId, sourceId, null);
    }
}
