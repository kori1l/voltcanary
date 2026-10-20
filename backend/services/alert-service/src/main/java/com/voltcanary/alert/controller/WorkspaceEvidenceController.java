package com.voltcanary.alert.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.alert.adapter.DashboardReadAuthorization;
import com.voltcanary.alert.repository.DashboardIncidentRepository;
import com.voltcanary.alert.config.AlertInternalProperties;
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
    private final DashboardIncidentRepository repository;
    private final String serviceToken;
    public WorkspaceEvidenceController(DashboardReadAuthorization access,DashboardIncidentRepository repository,AlertInternalProperties properties) {
        this.access=access;
        this.repository=repository;
        serviceToken=properties.getServiceToken();
    }
    @PostMapping("/alerts/workspaces/{workspaceId}/query")
    public ObjectNode read(@RequestHeader("Authorization") String authorization,@PathVariable("workspaceId") UUID workspaceId,
            @RequestBody DashboardOwnerReadRequest request) {
        if (request==null || !workspaceId.equals(request.workspaceId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Explicit workspace context required");
        return repository.read(request,access.authorize(serviceToken,authorization,request));
    }
}
