package com.voltcanary.telemetry.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.telemetry.service.DashboardEvidenceReads;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DashboardTelemetryController {

    private final DashboardEvidenceReads reads;

    @PostMapping("/internal/dashboard/telemetry")
    public ObjectNode read(
        @RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody DashboardOwnerReadRequest request
    ) {
        return reads.read(token, authorization, request);
    }
}
