package com.voltcanary.analytics.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.adapter.DashboardReadAuthorization;
import com.voltcanary.analytics.repository.DashboardAnalyticsRepository;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class WorkspaceEvidenceController {
    private final DashboardReadAuthorization access;
    private final DashboardAnalyticsRepository repository;
    private final String serviceToken;
    public WorkspaceEvidenceController(DashboardReadAuthorization access,DashboardAnalyticsRepository repository,AnalyticsInternalProperties properties) {
        this.access=access;
        this.repository=repository;
        serviceToken=properties.getServiceToken();
    }
    @PostMapping("/analytics/workspaces/{workspaceId}/query")
    public ObjectNode read(@RequestHeader("Authorization") String authorization,@PathVariable("workspaceId") UUID workspaceId,
            @RequestBody DashboardOwnerReadRequest request) {
        if (request==null || !workspaceId.equals(request.workspaceId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Explicit workspace context required");
        return repository.read(request,access.authorize(serviceToken,authorization,request));
    }
}
