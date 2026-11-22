package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.port.NativeWorkspaceIdentity;
import com.voltcanary.device.workspace.CurrentWorkspaceMembership;
import com.voltcanary.device.workspace.WorkspaceResourceAccess;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** Reuses the actual identity owner; no role or team membership comes from request JSON. */
@RequiredArgsConstructor
public final class WorkspaceNativeIdentity implements NativeWorkspaceIdentity {

    private final CurrentWorkspaceMembership memberships;
    private final WorkspaceResourceAccess resources;

    @Override
    public Membership membership(UUID workspace, UUID actor) {
        var result = memberships.require(workspace, actor);
        if (
            result == null ||
            !workspace.equals(result.workspaceId()) ||
            !actor.equals(result.userId()) ||
            result.role() == null ||
            result.version() < 0 ||
            result.teamIds() == null ||
            result.teamIds().contains(null) ||
            result.teamIds().size() > 1000
        ) throw new VclException("WORKSPACE_AUTHORITY_UNAVAILABLE", "");
        return new Membership(
            workspace,
            actor,
            result.role(),
            result.version(),
            Set.copyOf(result.teamIds())
        );
    }

    @Override
    public boolean teamExists(UUID workspace, UUID team) {
        return memberships.teamExists(workspace, team);
    }

    @Override
    public ResourceReads resources(UUID workspace, UUID account, UUID actor) {
        var pinned = resources.resolvePinned(workspace, account, actor, "device.read");
        var actual = pinned.membership();
        var member = new Membership(
            actual.workspaceId(),
            actual.userId(),
            actual.role(),
            actual.version(),
            Set.copyOf(actual.teamIds())
        );
        return new ResourceReads(
            member.digest(),
            pinned.associationVersion(),
            pinned.decision().deviceIds(),
            VclCanonical.sha256(pinned.decision().fingerprint())
        );
    }
}
