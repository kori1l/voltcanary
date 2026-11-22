package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.service.DashboardMaintenanceReads;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardMaintenanceController {

    private final DashboardMaintenanceReads useCase;

    public DashboardMaintenanceController(DashboardMaintenanceReads useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/workspaces/{workspace}/maintenance/query")
    public ObjectNode workspaceRead(
        @org.springframework.web.bind.annotation.PathVariable java.util.UUID workspace,
        @RequestHeader("Authorization") String authorization,
        @RequestBody DashboardOwnerReadRequest request
    ) {
        return useCase.workspaceRead(workspace, authorization, request);
    }

    @PostMapping("/internal/dashboard/maintenance")
    public ObjectNode read(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody DashboardOwnerReadRequest request
    ) {
        return useCase.read(token, authorization, request);
    }
}
