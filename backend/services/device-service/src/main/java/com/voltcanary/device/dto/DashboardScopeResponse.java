package com.voltcanary.device.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DashboardScopeResponse(UUID userId, UUID accountId, long requestedCount, long evaluatedCount,
        long deniedBindingCount, List<DeviceEntry> devices, List<LocationEntry> locations,
        boolean deviceTagsSupported, Instant resolvedAt) {
    public record DeviceEntry(UUID id, String name, String type, UUID locationId, String registrationStatus,
            Long cadenceSeconds, String cadenceSource, String sourcePolicy, Instant updatedAt) {
    }

    public record LocationEntry(UUID id, String name, UUID parentId, List<String> tags) {
    }
}
