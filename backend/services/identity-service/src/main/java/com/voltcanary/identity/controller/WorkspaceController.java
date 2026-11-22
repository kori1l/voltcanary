package com.voltcanary.identity.controller;

import com.voltcanary.identity.service.AuthService;
import com.voltcanary.identity.workspace.WorkspaceAdministration;
import com.voltcanary.identity.workspace.WorkspaceMembership.Member;
import com.voltcanary.identity.workspace.WorkspaceMembership.MemberRequest;
import com.voltcanary.identity.workspace.WorkspaceMembership.Snapshot;
import com.voltcanary.identity.workspace.WorkspaceMembership.Team;
import com.voltcanary.identity.workspace.WorkspaceMembership.TeamRequest;
import com.voltcanary.identity.workspace.WorkspaceMembership.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class WorkspaceController {

    public record Create(String name) {}

    public record Transfer(UUID userId, boolean reviewed) {}

    private final AuthService auth;
    private final WorkspaceAdministration workspaces;
    private final String serviceToken;

    public WorkspaceController(
        AuthService auth,
        WorkspaceAdministration workspaces,
        com.voltcanary.identity.config.IdentityDownstreamProperties properties
    ) {
        this.auth = auth;
        this.workspaces = workspaces;
        this.serviceToken = properties.getInternalServiceToken();
    }

    @GetMapping("/workspaces")
    public List<Workspace> list(@RequestHeader("Authorization") String authorization) {
        return workspaces.list(auth.me(authorization).id());
    }

    @PostMapping("/workspaces")
    public Workspace create(
        @RequestHeader("Authorization") String authorization,
        @RequestBody Create request
    ) {
        return workspaces.create(auth.me(authorization).id(), request.name());
    }

    @GetMapping("/workspaces/{id}/members")
    public List<Member> members(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("id") UUID id
    ) {
        return workspaces.members(auth.me(authorization).id(), id);
    }

    @PutMapping("/workspaces/{id}/members")
    public Map<String, Boolean> member(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("id") UUID id,
        @RequestBody MemberRequest request
    ) {
        workspaces.saveMember(auth.me(authorization).id(), id, request);
        return Map.of("saved", true);
    }

    @DeleteMapping("/workspaces/{id}/members/{userId}")
    public Map<String, Boolean> remove(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("id") UUID id,
        @PathVariable("userId") UUID userId
    ) {
        workspaces.remove(auth.me(authorization).id(), id, userId);
        return Map.of("removed", true);
    }

    @GetMapping("/workspaces/{id}/teams")
    public List<Team> teams(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("id") UUID id
    ) {
        return workspaces.teams(auth.me(authorization).id(), id);
    }

    @PostMapping("/workspaces/{id}/teams")
    public Map<String, UUID> team(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("id") UUID id,
        @RequestBody TeamRequest request
    ) {
        return Map.of("id", workspaces.addTeam(auth.me(authorization).id(), id, request));
    }

    @PostMapping("/workspaces/{id}/transfer")
    public Map<String, Boolean> transfer(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("id") UUID id,
        @RequestBody Transfer request
    ) {
        workspaces.transfer(auth.me(authorization).id(), id, request.userId(), request.reviewed());
        return Map.of("transferred", true);
    }

    @GetMapping("/workspaces/{id}/audit")
    public List<Map<String, Object>> audit(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("id") UUID id
    ) {
        return workspaces.audit(auth.me(authorization).id(), id);
    }

    @GetMapping("/internal/workspaces/{id}/membership")
    public Snapshot lookup(
        @RequestHeader("X-Internal-Service-Token") String token,
        @PathVariable("id") UUID id,
        @RequestParam(value = "userId") UUID userId
    ) {
        requireService(token);
        return workspaces.snapshot(userId, id);
    }

    @GetMapping("/internal/workspaces/{id}/teams/{teamId}")
    public Map<String, Boolean> teamExists(
        @RequestHeader("X-Internal-Service-Token") String token,
        @PathVariable("id") UUID id,
        @PathVariable("teamId") UUID teamId
    ) {
        requireService(token);
        return Map.of("exists", workspaces.teamExists(id, teamId));
    }

    private void requireService(String token) {
        if (
            token == null ||
            serviceToken.isBlank() ||
            !java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                serviceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            )
        ) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Service authentication required"
            );
        }
    }
}
