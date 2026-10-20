package com.voltcanary.common.web;

import java.util.UUID;
import java.util.Map;

public record DashboardPositionReadRequest(DashboardOwnerReadRequest scope, UUID deviceId, String sourceId,
        Map<UUID, String> vehicleSources) {
    public DashboardPositionReadRequest(DashboardOwnerReadRequest scope, UUID deviceId, String sourceId) {
        this(scope, deviceId, sourceId, null);
    }
}
