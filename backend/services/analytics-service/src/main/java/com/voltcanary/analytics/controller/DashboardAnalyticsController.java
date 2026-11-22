package com.voltcanary.analytics.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.service.DashboardEvidenceReads;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DashboardAnalyticsController {

    private final DashboardEvidenceReads reads;

    @PostMapping("/internal/dashboard/analytics")
    public ObjectNode read(
        @RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody DashboardOwnerReadRequest request
    ) {
        return reads.read(token, authorization, request);
    }
}
