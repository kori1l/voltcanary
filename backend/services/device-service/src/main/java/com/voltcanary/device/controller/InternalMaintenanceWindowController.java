package com.voltcanary.device.controller;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.dto.MaintenanceWindowResponse;
import com.voltcanary.device.service.MaintenanceWindowService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class InternalMaintenanceWindowController {

    private final MaintenanceWindowService service;
    private final InternalServiceAuth internalServiceAuth;

    public InternalMaintenanceWindowController(MaintenanceWindowService service, InternalServiceAuth internalServiceAuth) {
        this.service = service;
        this.internalServiceAuth = internalServiceAuth;
    }

    @GetMapping("/devices/{deviceId}/maintenance-windows")
    public List<MaintenanceWindowResponse> listForDevice(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @PathVariable("deviceId") UUID deviceId,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "includeCancelled", defaultValue = "false") boolean includeCancelled) {
        internalServiceAuth.require(internalToken);
        return service.listInternal(deviceId, from, to, includeCancelled);
    }

    @GetMapping("/maintenance-windows/changes")
    public List<MaintenanceWindowResponse> changes(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @RequestParam(value = "updatedAfter", required = false) Instant updatedAfter,
            @RequestParam(value = "limit", required = false) Integer limit) {
        internalServiceAuth.require(internalToken);
        return service.changes(updatedAfter, limit);
    }
}
