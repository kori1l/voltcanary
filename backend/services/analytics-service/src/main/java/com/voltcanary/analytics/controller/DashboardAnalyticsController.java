package com.voltcanary.analytics.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.adapter.DashboardReadAuthorization;
import com.voltcanary.analytics.repository.DashboardAnalyticsRepository;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardAnalyticsController {
    private final DashboardReadAuthorization access;
    private final DashboardAnalyticsRepository repository;

    public DashboardAnalyticsController(DashboardReadAuthorization access, DashboardAnalyticsRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    @PostMapping("/internal/dashboard/analytics")
    public ObjectNode read(@RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody DashboardOwnerReadRequest request) {
        return repository.read(request, access.authorize(token, authorization, request));
    }
}
