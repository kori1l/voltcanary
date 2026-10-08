package com.voltcanary.telemetry.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.telemetry.adapter.DashboardReadAuthorization;
import com.voltcanary.telemetry.repository.DashboardTelemetryRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardTelemetryController {
    private final DashboardReadAuthorization access;
    private final DashboardTelemetryRepository repository;

    public DashboardTelemetryController(DashboardReadAuthorization access, DashboardTelemetryRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    @PostMapping("/internal/dashboard/telemetry")
    public ObjectNode read(@RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody DashboardOwnerReadRequest request) {
        return repository.read(request, access.authorize(token, authorization, request));
    }
}
