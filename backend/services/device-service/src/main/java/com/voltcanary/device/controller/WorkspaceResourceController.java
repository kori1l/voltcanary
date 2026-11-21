package com.voltcanary.device.controller;

import com.voltcanary.device.workspace.CurrentWorkspaceMembership;
import com.voltcanary.device.workspace.WorkspaceResourceAccess;
import lombok.RequiredArgsConstructor;

import com.voltcanary.common.security.WorkspaceAuthorization.Decision;
import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.workspace.ResourceGrantRepository.Association;
import com.voltcanary.device.workspace.ResourceGrantRepository.Grant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WorkspaceResourceController {
    public record Consent(UUID accountId,boolean reviewed,boolean active) {
    }
    public record GrantRequest(Grant grant,boolean reviewed) {
    }
    private final WorkspaceResourceAccess access;
    private final AuthenticatedUserResolver users;
    private final InternalServiceAuth internal;
    private final CurrentWorkspaceMembership membership;
    private final com.voltcanary.device.service.DashboardScopeReadService scopes;

    @GetMapping("/workspaces/{workspaceId}/resource-accounts")
    public List<Association> accounts(@RequestHeader HttpHeaders headers,@PathVariable("workspaceId") UUID workspaceId) {
        return access.associations(actor(headers),workspaceId);
    }
    @PostMapping("/workspaces/{workspaceId}/resource-accounts")
    public Map<String,Boolean> associate(@RequestHeader HttpHeaders headers,@PathVariable("workspaceId") UUID workspaceId,@RequestBody Consent consent) {
        access.associate(actor(headers),workspaceId,consent.accountId(),consent.reviewed(),consent.active());
        return Map.of("saved",true);
    }
    @GetMapping("/workspaces/{workspaceId}/resource-grants")
    public List<Grant> grants(@RequestHeader HttpHeaders headers,@PathVariable("workspaceId") UUID workspaceId,@RequestParam(value="accountId") UUID accountId) {
        return access.list(actor(headers),workspaceId,accountId);
    }
    @PostMapping("/workspaces/{workspaceId}/resource-grants")
    public Map<String,Boolean> grant(@RequestHeader HttpHeaders headers,@PathVariable("workspaceId") UUID workspaceId,@RequestBody GrantRequest request) {
        access.save(actor(headers),workspaceId,request.grant(),request.reviewed());
        return Map.of("saved",true);
    }
    @DeleteMapping("/workspaces/{workspaceId}/resource-grants/{grantId}")
    public Map<String,Boolean> revoke(@RequestHeader HttpHeaders headers,@PathVariable("workspaceId") UUID workspaceId,@PathVariable("grantId") UUID grantId,@RequestParam(value="accountId") UUID accountId) {
        access.revoke(actor(headers),workspaceId,accountId,grantId);
        return Map.of("revoked",true);
    }
    @GetMapping("/workspaces/{workspaceId}/resource-audit")
    public List<Map<String,Object>> audit(@RequestHeader HttpHeaders headers,@PathVariable("workspaceId") UUID workspaceId,@RequestParam(value="accountId") UUID accountId) {
        return access.audit(actor(headers),workspaceId,accountId);
    }
    @GetMapping("/internal/workspaces/{workspaceId}/resource-access")
    public Decision decision(@RequestHeader("X-Internal-Service-Token") String token,@PathVariable("workspaceId") UUID workspaceId,
            @RequestParam(value="accountId") UUID accountId,@RequestParam(value="userId") UUID userId,@RequestParam(value="action") String action) {
        internal.require(token);
        return access.resolve(workspaceId,accountId,userId,action);
    }
    private UUID actor(HttpHeaders headers) {
        HttpHeaders signed=new HttpHeaders();
        signed.set(HttpHeaders.AUTHORIZATION,headers.getFirst(HttpHeaders.AUTHORIZATION));
        return users.resolve(signed).id();
    }
    @PostMapping("/workspaces/{workspaceId}/devices/query")
    public com.voltcanary.device.dto.DashboardScopeResponse devices(@RequestHeader HttpHeaders headers,@PathVariable("workspaceId") UUID workspaceId,@RequestBody com.voltcanary.device.dto.DashboardScopeRequest request) {
        UUID user=actor(headers);
        return scopes.read(new com.voltcanary.device.security.AuthenticatedUser(user,"","USER"),new com.voltcanary.device.dto.DashboardScopeRequest(request.mode(),request.deviceIds(),request.locationIds(),request.includeSubtree(),request.deviceTypes(),request.locationTags(),workspaceId,request.accountId(),"device.read"));
    }
    @GetMapping("/internal/workspaces/{workspaceId}/membership")
    public com.voltcanary.common.security.WorkspaceAuthorization.Membership membership(@RequestHeader("X-Internal-Service-Token") String token,@PathVariable("workspaceId") UUID workspaceId,@RequestParam(value="userId") UUID userId) {
        internal.require(token);
        return membership.require(workspaceId,userId);
    }
    @GetMapping("/internal/workspaces/{workspaceId}/teams/{teamId}")
    public Map<String,Boolean> team(@RequestHeader("X-Internal-Service-Token") String token,@PathVariable("workspaceId") UUID workspaceId,@PathVariable("teamId") UUID teamId) {
        internal.require(token);
        return Map.of("exists",membership.teamExists(workspaceId,teamId));
    }
}
