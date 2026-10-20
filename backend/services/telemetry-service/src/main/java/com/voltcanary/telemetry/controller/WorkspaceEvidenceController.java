package com.voltcanary.telemetry.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.telemetry.adapter.DashboardReadAuthorization;
import com.voltcanary.telemetry.repository.DashboardTelemetryRepository;
import com.voltcanary.telemetry.config.TelemetryAuthorizationProperties;
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
    private final DashboardTelemetryRepository repository;
    private final String serviceToken;
    private final com.voltcanary.telemetry.service.TelemetryQueryService queries;
    public WorkspaceEvidenceController(DashboardReadAuthorization access,DashboardTelemetryRepository repository,TelemetryAuthorizationProperties properties,com.voltcanary.telemetry.service.TelemetryQueryService queries) {
        this.access=access;
        this.repository=repository;
        serviceToken=properties.getInternalServiceToken();
        this.queries=queries;
    }
    @PostMapping("/telemetry/workspaces/{workspaceId}/query")
    public ObjectNode read(@RequestHeader("Authorization") String authorization,@PathVariable("workspaceId") UUID workspaceId,
            @RequestBody DashboardOwnerReadRequest request) {
        if (request==null || !workspaceId.equals(request.workspaceId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Explicit workspace context required");
        return repository.read(request,access.authorize(serviceToken,authorization,request));
    }
    @org.springframework.web.bind.annotation.GetMapping("/telemetry/workspaces/{workspace}/devices/{device}/metrics")
    public java.util.List<com.voltcanary.telemetry.dto.TelemetryMetricResponse> metrics(@RequestHeader("Authorization") String authorization,
            @RequestHeader("X-User-Id") UUID user,@PathVariable("workspace") UUID workspace,@PathVariable("device") UUID device,
            @org.springframework.web.bind.annotation.RequestParam("accountId") UUID account,
            @org.springframework.web.bind.annotation.RequestParam("from") java.time.Instant from,
            @org.springframework.web.bind.annotation.RequestParam("to") java.time.Instant to) {
        var request=new DashboardOwnerReadRequest(user,account,java.util.List.of(device),new DashboardOwnerReadRequest.TimeFrame(from,to,java.time.Instant.now(),null,null),java.util.Set.of(),java.util.List.of(),1,0,"ACTIVE",java.util.List.of(),workspace);
        if(!access.authorize(serviceToken,authorization,request).contains(device)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Current telemetry capability unavailable");
        return queries.getMetrics(device,from,to);
    }
}
