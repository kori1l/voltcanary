package com.voltcanary.alert.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.alert.adapter.DashboardReadAuthorization;
import com.voltcanary.alert.repository.DashboardIncidentRepository;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardIncidentController {
    private final DashboardReadAuthorization access;
    private final DashboardIncidentRepository repository;

    public DashboardIncidentController(DashboardReadAuthorization access, DashboardIncidentRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    @PostMapping("/internal/dashboard/incidents")
    public ObjectNode read(@RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody DashboardOwnerReadRequest request) {
        return repository.read(request, access.authorize(token, authorization, request));
    }
}
