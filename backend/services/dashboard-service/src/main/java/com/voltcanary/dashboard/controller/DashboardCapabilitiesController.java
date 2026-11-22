package com.voltcanary.dashboard.controller;

import com.voltcanary.dashboard.application.DashboardCapabilityResolution;
import com.voltcanary.dashboard.domain.DashboardDataChoices.Resolution;
import com.voltcanary.dashboard.port.DashboardOwnership;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DashboardCapabilitiesController {

    private final DashboardOwnership ownership;
    private final DashboardCapabilityResolution resolver;

    @PostMapping("/dashboards/composition/capabilities")
    public Resolution capabilities(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody DashboardCapabilityResolution.Request request,
        jakarta.servlet.http.HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return resolver.resolve(ownership.resolveReadOnly(authorization), authorization, request);
    }
}
