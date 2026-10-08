package com.voltcanary.device.controller;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.dto.DashboardScopeRequest;
import com.voltcanary.device.dto.DashboardScopeResponse;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.DashboardScopeReadService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardScopeController {
    private final InternalServiceAuth internal;
    private final AuthenticatedUserResolver users;
    private final DashboardScopeReadService scopes;

    public DashboardScopeController(InternalServiceAuth internal, AuthenticatedUserResolver users, DashboardScopeReadService scopes) {
        this.internal = internal;
        this.users = users;
        this.scopes = scopes;
    }

    @PostMapping("/internal/dashboard/scope")
    public DashboardScopeResponse scope(@RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody DashboardScopeRequest request) {
        internal.require(token);
        HttpHeaders signedActorOnly = new HttpHeaders();
        if (authorization != null) signedActorOnly.set(HttpHeaders.AUTHORIZATION, authorization);
        return scopes.read(users.resolve(signedActorOnly), request);
    }
}
