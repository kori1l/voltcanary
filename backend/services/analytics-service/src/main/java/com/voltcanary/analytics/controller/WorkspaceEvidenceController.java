package com.voltcanary.analytics.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.service.DashboardEvidenceReads;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
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

    @PostMapping("/analytics/workspaces/{workspaceId}/query")
    public ObjectNode read(
        @RequestHeader("Authorization") String authorization,
        @PathVariable("workspaceId") UUID workspaceId,
        @RequestBody DashboardOwnerReadRequest request
    ) {
        return reads.workspace(authorization, workspaceId, request);
    }
}
