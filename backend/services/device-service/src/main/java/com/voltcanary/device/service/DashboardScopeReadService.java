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

    public DashboardScopeReadService(AccountService accounts, DashboardScopeRepository repository) {
        this.accounts = accounts;
        this.repository = repository;
    }

    public DashboardScopeResponse read(AuthenticatedUser actor, DashboardScopeRequest request) {
        if (request == null || !List.of("ALL", "DYNAMIC", "FIXED", "UNBOUND").contains(request.mode())
                || request.deviceIds().size() > 500 || request.locationIds().size() > 500
                || request.deviceTypes().size() > 50 || request.locationTags().size() > 50
                || request.deviceTypes().stream().anyMatch(value -> value.length() > 100)
                || request.locationTags().stream().anyMatch(value -> value.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid or oversized typed dashboard scope");
        }
        if ("FIXED".equals(request.mode()) && request.deviceIds().isEmpty() && request.locationIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Fixed scope requires explicit resource IDs");
        }
        var account = accounts.findByUserId(actor.id()).filter(candidate -> candidate.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Active account is required"));
        return repository.resolve(actor.id(), account.getId(), request);
    }
}
