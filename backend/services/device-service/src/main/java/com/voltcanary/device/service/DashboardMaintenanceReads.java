package com.voltcanary.device.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.dto.DashboardScopeRequest;
import com.voltcanary.device.repository.DashboardMaintenanceRepository;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.DashboardScopeReadService;
import java.time.Duration;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
public class DashboardMaintenanceReads {

    private final InternalServiceAuth internal;
    private final AuthenticatedUserResolver users;
    private final DashboardScopeReadService scopes;
    private final DashboardMaintenanceRepository repository;
    private final String serviceToken;

    public ObjectNode workspaceRead(
        java.util.UUID workspace,
        String authorization,
        DashboardOwnerReadRequest request
    ) {
        if (
            request == null || !workspace.equals(request.workspaceId())
        ) throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Explicit workspace context required"
        );
        return read(serviceToken, authorization, request);
    }

    public DashboardMaintenanceReads(
        InternalServiceAuth internal,
        AuthenticatedUserResolver users,
        DashboardScopeReadService scopes,
        DashboardMaintenanceRepository repository,
        com.voltcanary.device.config.InternalServiceProperties properties
    ) {
        this.internal = internal;
        this.users = users;
        this.scopes = scopes;
        this.repository = repository;
        serviceToken = properties.getServiceToken();
    }

    public ObjectNode read(String token, String authorization, DashboardOwnerReadRequest request) {
        internal.require(token);
        HttpHeaders signedOnly = new HttpHeaders();
        if (authorization != null) signedOnly.set(HttpHeaders.AUTHORIZATION, authorization);
        var user = users.resolve(signedOnly);
        if (
            request == null ||
            request.userId() == null ||
            request.accountId() == null ||
            !user.id().equals(request.userId()) ||
            request.deviceIds().size() > 500 ||
            request.rows() < 1 ||
            request.rows() > 50 ||
            request.offset() < 0 ||
            request.offset() > 1000000 ||
            request.time() == null ||
            request.time().asOf() == null ||
            request.time().from() == null ||
            request.time().to() == null ||
            !request.time().from().isBefore(request.time().to()) ||
            Duration.between(request.time().from(), request.time().to()).compareTo(
                Duration.ofDays(31)
            ) > 0
        ) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid typed maintenance read"
            );
        }
        var scope = scopes.read(
            user,
            new DashboardScopeRequest(
                request.deviceIds().isEmpty() ? "UNBOUND" : "FIXED",
                Set.copyOf(request.deviceIds()),
                Set.of(),
                false,
                Set.of(),
                Set.of(),
                request.workspaceId(),
                request.accountId(),
                "maintenance.read"
            )
        );
        if (!scope.accountId().equals(request.accountId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Read account does not match current ownership"
            );
        }
        return repository.read(
            request,
            scope
                .devices()
                .stream()
                .map(device -> device.id())
                .toList()
        );
    }
}
