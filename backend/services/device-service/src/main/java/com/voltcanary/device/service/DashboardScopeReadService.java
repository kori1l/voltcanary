package com.voltcanary.device.service;

import com.voltcanary.device.dto.DashboardScopeRequest;
import com.voltcanary.device.dto.DashboardScopeResponse;
import com.voltcanary.device.repository.DashboardScopeRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.domain.AccountStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DashboardScopeReadService {
    private final AccountService accounts;
    private final DashboardScopeRepository repository;
    private final com.voltcanary.device.workspace.WorkspaceResourceAccess workspace;

    public DashboardScopeReadService(AccountService accounts, DashboardScopeRepository repository,com.voltcanary.device.workspace.WorkspaceResourceAccess workspace) {
        this.accounts = accounts;
        this.repository = repository;
        this.workspace=workspace;
    }

    public DashboardScopeResponse read(AuthenticatedUser actor, DashboardScopeRequest request) {
        if (request == null || !List.of("ALL", "DYNAMIC", "FIXED", "UNBOUND").contains(request.mode())
                || request.deviceIds().size() > 500 || request.locationIds().size() > 500
                || request.deviceTypes().size() > 50 || request.locationTags().size() > 50
                || request.deviceTypes().stream().anyMatch(value -> value.length() > 100)
                || request.locationTags().stream().anyMatch(value -> value.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid or oversized typed dashboard scope");
        }
        if ("FIXED".equals(request.mode()) && request.deviceIds().isEmpty() && request.locationIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Fixed scope requires explicit resource IDs");
        }
        var account = accounts.findByUserId(actor.id()).filter(candidate -> candidate.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Active account is required"));
        if (request.workspaceId()!=null) {
            if (request.accountId()==null || workspace==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Explicit workspace account required");
            var decision=workspace.resolve(request.workspaceId(),request.accountId(),actor.id(),request.action()==null?"device.read":request.action());
            var result=repository.resolve(actor.id(),request.accountId(),request,decision.deviceIds());
            return new DashboardScopeResponse(result.userId(),result.accountId(),result.evaluatedCount(),result.evaluatedCount(),0,result.devices(),result.locations(),false,result.resolvedAt(),request.workspaceId(),decision.fingerprint());
        }
        return repository.resolve(actor.id(), account.getId(), request);
    }
}
