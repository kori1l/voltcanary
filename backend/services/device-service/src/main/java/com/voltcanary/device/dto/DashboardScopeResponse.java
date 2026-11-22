package com.voltcanary.device.dto;

import com.voltcanary.common.business.EnergyMeterDefinition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DashboardScopeResponse(
    UUID userId,
    UUID accountId,
    long requestedCount,
    long evaluatedCount,
    long deniedBindingCount,
    List<DeviceEntry> devices,
    List<LocationEntry> locations,
    boolean deviceTagsSupported,
    Instant resolvedAt,
    UUID workspaceId,
    String authorizationFingerprint
) {
    public DashboardScopeResponse(
        UUID userId,
        UUID accountId,
        long requestedCount,
        long evaluatedCount,
        long deniedBindingCount,
        List<DeviceEntry> devices,
        List<LocationEntry> locations,
        boolean deviceTagsSupported,
        Instant resolvedAt
    ) {
        this(
            userId,
            accountId,
            requestedCount,
            evaluatedCount,
            deniedBindingCount,
            devices,
            locations,
            deviceTagsSupported,
            resolvedAt,
            null,
            null
        );
    }

    public record DeviceEntry(
        UUID id,
        String name,
        String type,
        UUID locationId,
        String registrationStatus,
        Long cadenceSeconds,
        String cadenceSource,
        String sourcePolicy,
        Instant updatedAt,
        List<EnergyMeterDefinition> energyMeters
    ) {
        public DeviceEntry(
            UUID id,
            String name,
            String type,
            UUID locationId,
            String registrationStatus,
            Long cadenceSeconds,
            String cadenceSource,
            String sourcePolicy,
            Instant updatedAt
        ) {
            this(
                id,
                name,
                type,
                locationId,
                registrationStatus,
                cadenceSeconds,
                cadenceSource,
                sourcePolicy,
                updatedAt,
                List.of()
            );
        }
    }

    public record LocationEntry(UUID id, String name, UUID parentId, List<String> tags) {}
}
