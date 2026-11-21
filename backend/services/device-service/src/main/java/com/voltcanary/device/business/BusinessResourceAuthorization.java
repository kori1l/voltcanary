package com.voltcanary.device.business;

import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.AccountStatus;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.AccountService;
import com.voltcanary.device.workspace.WorkspaceResourceAccess;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class BusinessResourceAuthorization {
    private final AccountService accounts;
    private final DeviceRepository devices;
    private final WorkspaceResourceAccess workspace;

    public UUID account(AuthenticatedUser actor,UUID accountId,UUID workspaceId,String action) {
        Account own=accounts.findByUserId(actor.id()).filter(a->a.getStatus()==AccountStatus.ACTIVE)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Active account required"));
        UUID account=accountId==null?own.getId():accountId;
        if (workspaceId==null) {
            if (!own.getId().equals(account)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account access unavailable");
        } else if (workspace.resolve(workspaceId,account,actor.id(),action).deviceIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Explicit scoped capability required");
        }
        return account;
    }
    public void device(AuthenticatedUser actor,UUID accountId,UUID workspaceId,String action,UUID deviceId) {
        UUID account=account(actor,accountId,workspaceId,action);
        if (devices.findByIdAndAccountId(deviceId,account).isEmpty()
                || workspaceId!=null && !workspace.resolve(workspaceId,account,actor.id(),action).deviceIds().contains(deviceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Device capability unavailable");
        }
    }
    public Set<UUID> subset(AuthenticatedUser actor,UUID accountId,UUID workspaceId,String action,Set<UUID> requested) {
        UUID account=account(actor,accountId,workspaceId,action);
        Set<UUID> result=new java.util.HashSet<>(requested);
        if (workspaceId!=null) result.retainAll(workspace.resolve(workspaceId,account,actor.id(),action).deviceIds());
        else result.removeIf(id->devices.findByIdAndAccountId(id,account).isEmpty());
        return Set.copyOf(result);
    }
}
