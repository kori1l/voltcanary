package com.voltcanary.device.controller;

import com.voltcanary.device.dto.CancelMaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceWindowResponse;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.MaintenanceWindowService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices/{deviceId}/maintenance-windows")
public class MaintenanceWindowController {

    private final MaintenanceWindowService service;
    private final AuthenticatedUserResolver userResolver;

    public MaintenanceWindowController(MaintenanceWindowService service, AuthenticatedUserResolver userResolver) {
        this.service = service;
        this.userResolver = userResolver;
    }

    @GetMapping
    public List<MaintenanceWindowResponse> list(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        return service.list(userResolver.resolve(headers), deviceId, from, to, status, limit, offset);
    }

    @PostMapping
    public MaintenanceWindowResponse create(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody MaintenanceWindowRequest request) {
        return service.create(userResolver.resolve(headers), deviceId, request);
    }

    @GetMapping("/{windowId}")
    public MaintenanceWindowResponse get(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("windowId") UUID windowId) {
        return service.get(userResolver.resolve(headers), deviceId, windowId);
    }

    @PatchMapping("/{windowId}")
    public MaintenanceWindowResponse update(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("windowId") UUID windowId,
            @Valid @RequestBody MaintenanceWindowRequest request) {
        return service.update(userResolver.resolve(headers), deviceId, windowId, request);
    }

    @PostMapping("/{windowId}/cancel")
    public MaintenanceWindowResponse cancel(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("windowId") UUID windowId,
            @Valid @RequestBody(required = false) CancelMaintenanceWindowRequest request) {
        return service.cancel(userResolver.resolve(headers), deviceId, windowId, request);
    }

    @PostMapping("/{windowId}/finish-now")
    public MaintenanceWindowResponse finishNow(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("windowId") UUID windowId) {
        return service.finishNow(userResolver.resolve(headers), deviceId, windowId);
    }
}
