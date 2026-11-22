package com.voltcanary.common.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit collaboration context; identity roles never substitute for resource capabilities. */
public interface WorkspaceAuthorization {
    Set<String> ACTIONS = Set.of(
        "device.read",
        "telemetry.read",
        "analytics.read",
        "incident.read",
        "incident.ack",
        "incident.resolve",
        "maintenance.read",
        "maintenance.write",
        "ml.read",
        "ml.write",
        "tariff.read",
        "tariff.write",
        "operational.read",
        "operational.write"
    );

    record Membership(
        UUID workspaceId,
        UUID userId,
        String role,
        long version,
        List<UUID> teamIds
    ) {}

    record Decision(
        UUID workspaceId,
        UUID accountId,
        UUID userId,
        String action,
        Set<UUID> deviceIds,
        String fingerprint
    ) {}

    Decision resolve(UUID workspaceId, UUID accountId, UUID userId, String action);
}
