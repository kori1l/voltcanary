package com.voltcanary.telemetry.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardEnergyReadRequest;
import com.voltcanary.telemetry.adapter.DashboardReadAuthorization;
import com.voltcanary.telemetry.service.EnergyPeriodReadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardEnergyController {
    private final DashboardReadAuthorization access;
    private final EnergyPeriodReadService energy;

    public DashboardEnergyController(DashboardReadAuthorization access,EnergyPeriodReadService energy) {
        this.access = access;
        this.energy = energy;
    }

    @PostMapping("/internal/dashboard/energy")
    public ObjectNode read(@RequestHeader(value="X-Internal-Service-Token",required=false) String token,
            @RequestHeader(value="Authorization",required=false) String authorization,@RequestBody DashboardEnergyReadRequest request) {
        if (request==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"Energy scope is required");
        return energy.read(request,access.renewedScope(token,authorization,request.scope()));
    }

    @PostMapping("/internal/dashboard/energy-validation")
    public ObjectNode validate(@RequestHeader(value="X-Internal-Service-Token",required=false) String token,
            @RequestHeader(value="Authorization",required=false) String authorization,@RequestBody DashboardEnergyReadRequest request) {
        if (request==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"Energy scope is required");
        return energy.validate(request,access.renewedScope(token,authorization,request.scope()));
    }
}
