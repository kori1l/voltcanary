package com.voltcanary.telemetry.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.telemetry.service.DashboardEvidenceReads;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkspaceEvidenceController {

    private final DashboardEvidenceReads reads;

    public WorkspaceEvidenceController(DashboardEvidenceReads reads) {
        this.reads = reads;
    }

    @PostMapping("/telemetry/workspaces/{workspaceId}/query")
    public ObjectNode read(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("workspaceId") UUID workspaceId,
        @RequestBody DashboardOwnerReadRequest request
    ) {
        return reads.workspace(authorization, workspaceId, request);
    }

    @org.springframework.web.bind.annotation.GetMapping(
        "/telemetry/workspaces/{workspace}/devices/{device}/metrics"
    )
    public java.util.List<com.voltcanary.telemetry.dto.TelemetryMetricResponse> metrics(
        @RequestHeader("Authorization") String authorization,
        @RequestHeader("X-User-Id") UUID user,
        @PathVariable("workspace") UUID workspace,
        @PathVariable("device") UUID device,
        @org.springframework.web.bind.annotation.RequestParam("accountId") UUID account,
        @org.springframework.web.bind.annotation.RequestParam("from") java.time.Instant from,
        @org.springframework.web.bind.annotation.RequestParam("to") java.time.Instant to
    ) {
        return reads.metrics(authorization, user, workspace, device, account, from, to);
    }
}
