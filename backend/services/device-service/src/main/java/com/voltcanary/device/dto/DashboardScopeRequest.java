package com.voltcanary.device.dto;

import java.util.Set;
import java.util.UUID;

public record DashboardScopeRequest(
    String mode,
    Set<UUID> deviceIds,
    Set<UUID> locationIds,
    boolean includeSubtree,
    Set<String> deviceTypes,
    Set<String> locationTags,
    UUID workspaceId,
    UUID accountId,
    String action
) {
    public DashboardScopeRequest(
        String mode,
        Set<UUID> deviceIds,
        Set<UUID> locationIds,
        boolean includeSubtree,
        Set<String> deviceTypes,
        Set<String> locationTags
    ) {
        this(
            mode,
            deviceIds,
            locationIds,
            includeSubtree,
            deviceTypes,
            locationTags,
            null,
            null,
            null
        );
    }

    public DashboardScopeRequest {
        mode = mode == null ? "ALL" : mode;
        deviceIds = deviceIds == null ? Set.of() : Set.copyOf(deviceIds);
        locationIds = locationIds == null ? Set.of() : Set.copyOf(locationIds);
        deviceTypes = deviceTypes == null ? Set.of() : Set.copyOf(deviceTypes);
        locationTags = locationTags == null ? Set.of() : Set.copyOf(locationTags);
    }
}
