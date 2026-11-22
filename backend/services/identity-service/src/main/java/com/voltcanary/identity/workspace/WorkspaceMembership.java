package com.voltcanary.identity.workspace;

import java.util.List;
import java.util.UUID;

public final class WorkspaceMembership {

    private WorkspaceMembership() {}

    public record Workspace(UUID id, String name, UUID ownerUserId, long version) {}

    public record Member(UUID userId, String role, String status) {}

    public record Team(UUID id, String name, List<UUID> userIds) {}

    public record Snapshot(
        UUID workspaceId,
        UUID userId,
        String role,
        long version,
        List<UUID> teamIds
    ) {}

    public record MemberRequest(UUID userId, String role, boolean reviewed) {}

    public record TeamRequest(String name, List<UUID> userIds, boolean reviewed) {}
}
