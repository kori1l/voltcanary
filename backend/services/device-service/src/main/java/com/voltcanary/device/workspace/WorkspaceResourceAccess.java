package com.voltcanary.device.workspace;

import com.voltcanary.common.security.WorkspaceAuthorization;
import com.voltcanary.common.security.WorkspaceAuthorization.Membership;
import com.voltcanary.device.workspace.ResourceGrantRepository.Association;
import com.voltcanary.device.workspace.ResourceGrantRepository.Grant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkspaceResourceAccess implements WorkspaceAuthorization {

    public record Resolved(Membership membership, long associationVersion, Decision decision) {}

    private final CurrentWorkspaceMembership memberships;
    private final ResourceGrantRepository grants;

    @Override
    public Decision resolve(UUID workspace, UUID account, UUID user, String action) {
        return resolvePinned(workspace, account, user, action).decision();
    }

    public Resolved resolvePinned(UUID workspace, UUID account, UUID user, String action) {
        require(ACTIONS.contains(action), HttpStatus.UNPROCESSABLE_CONTENT);
        Membership member = memberships.require(workspace, user);
        Long version = grants.version(workspace, account, false);
        require(version != null && grants.activeActor(user), HttpStatus.FORBIDDEN);
        List<Grant> matching = grants
            .grants(workspace, account)
            .stream()
            .filter(
                grant ->
                    grant.action().equals(action) &&
                    (("USER".equals(grant.subjectKind()) && grant.subjectId().equals(user)) ||
                        ("TEAM".equals(grant.subjectKind()) &&
                            member.teamIds().contains(grant.subjectId())))
            )
            .toList();
        Set<UUID> devices = grants.devices(account, matching);
        require(devices.size() <= 500, HttpStatus.UNPROCESSABLE_CONTENT);
        // Includes the action and exact current authorized subset; never caches an old decision.
        String fingerprint =
            workspace +
            ":" +
            member.version() +
            ":" +
            version +
            ":" +
            action +
            ":" +
            devices.stream().sorted().toList();
        return new Resolved(
            member,
            version,
            new Decision(workspace, account, user, action, devices, fingerprint)
        );
    }

    public List<Association> associations(UUID actor, UUID workspace) {
        memberships.manager(workspace, actor);
        return grants.associations(workspace);
    }

    @Transactional
    public void associate(
        UUID actor,
        UUID workspace,
        UUID account,
        boolean consentReviewed,
        boolean active
    ) {
        require(consentReviewed && grants.owner(account, actor), HttpStatus.FORBIDDEN);
        if (active) memberships.require(workspace, actor);
        else require(grants.associated(workspace, account), HttpStatus.FORBIDDEN);
        grants.associate(workspace, account, actor, active);
        grants.changed(
            workspace,
            account,
            actor,
            active ? "ACCOUNT_ASSOCIATED" : "ACCOUNT_DETACHED",
            account
        );
    }

    public List<Grant> list(UUID actor, UUID workspace, UUID account) {
        manage(actor, workspace, account, false);
        return grants.grants(workspace, account);
    }

    @Transactional
    public void save(UUID actor, UUID workspace, Grant grant, boolean reviewed) {
        require(
            grant != null &&
                reviewed &&
                grant.accountId() != null &&
                grant.subjectId() != null &&
                grant.resourceId() != null &&
                ACTIONS.contains(grant.action()) &&
                List.of("USER", "TEAM").contains(grant.subjectKind()) &&
                List.of("DEVICE", "LOCATION").contains(grant.scopeKind()),
            HttpStatus.UNPROCESSABLE_CONTENT
        );
        manage(actor, workspace, grant.accountId(), true);
        require(
            grants.grants(workspace, grant.accountId()).size() < 1000,
            HttpStatus.UNPROCESSABLE_CONTENT
        );
        require(
            grants.resourceOwned(grant.accountId(), grant.scopeKind(), grant.resourceId()),
            HttpStatus.UNPROCESSABLE_CONTENT
        );
        if ("USER".equals(grant.subjectKind())) memberships.require(workspace, grant.subjectId());
        else require(grantsTeam(workspace, grant.subjectId()), HttpStatus.UNPROCESSABLE_CONTENT);
        grants.insert(
            workspace,
            new Grant(
                UUID.randomUUID(),
                grant.accountId(),
                grant.subjectId(),
                grant.subjectKind(),
                grant.action(),
                grant.scopeKind(),
                grant.resourceId()
            )
        );
        grants.changed(workspace, grant.accountId(), actor, "GRANT_REVIEWED", grant.subjectId());
    }

    private boolean grantsTeam(UUID workspace, UUID team) {
        // Team identity is checked by the identity authority through a dedicated service endpoint.
        return memberships.teamExists(workspace, team);
    }

    @Transactional
    public void revoke(UUID actor, UUID workspace, UUID account, UUID id) {
        manage(actor, workspace, account, true);
        require(grants.remove(workspace, account, id), HttpStatus.NOT_FOUND);
        grants.changed(workspace, account, actor, "GRANT_REVOKED", id);
    }

    public List<Map<String, Object>> audit(UUID actor, UUID workspace, UUID account) {
        manage(actor, workspace, account, false);
        return grants.audit(workspace, account);
    }

    private void manage(UUID actor, UUID workspace, UUID account, boolean lock) {
        memberships.manager(workspace, actor);
        require(
            grants.owner(account, actor) && grants.version(workspace, account, lock) != null,
            HttpStatus.FORBIDDEN
        );
    }

    private void require(boolean valid, HttpStatus status) {
        if (!valid) throw new ResponseStatusException(
            status,
            "Resource access operation unavailable"
        );
    }
}
