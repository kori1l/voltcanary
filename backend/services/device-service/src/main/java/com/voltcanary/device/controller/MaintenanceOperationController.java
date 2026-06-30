package com.voltcanary.device.controller;

import com.voltcanary.device.dto.CancelMaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceOperationRequest;
import com.voltcanary.device.dto.MaintenanceOperationResponse;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.MaintenanceOperationService;
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
@RequestMapping("/maintenance-operations")
public class MaintenanceOperationController {

    private final MaintenanceOperationService service;
    private final AuthenticatedUserResolver userResolver;

    public MaintenanceOperationController(MaintenanceOperationService service, AuthenticatedUserResolver userResolver) {
        this.service = service;
        this.userResolver = userResolver;
    }

    @GetMapping
    public List<MaintenanceOperationResponse> list(
            @RequestHeader HttpHeaders headers,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "deviceIds", required = false) List<UUID> deviceIds,
            @RequestParam(value = "locationId", required = false) UUID locationId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "includeCancelled", defaultValue = "false") boolean includeCancelled,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        return service.list(userResolver.resolve(headers), from, to, deviceIds, locationId, status, includeCancelled, limit, offset);
    }

    @PostMapping
    public MaintenanceOperationResponse create(
            @RequestHeader HttpHeaders headers,
            @Valid @RequestBody MaintenanceOperationRequest request) {
        return service.create(userResolver.resolve(headers), request);
    }

    @GetMapping("/{operationId}")
    public MaintenanceOperationResponse get(
            @RequestHeader HttpHeaders headers,
            @PathVariable("operationId") UUID operationId) {
        return service.get(userResolver.resolve(headers), operationId);
    }

    @PatchMapping("/{operationId}")
    public MaintenanceOperationResponse update(
            @RequestHeader HttpHeaders headers,
            @PathVariable("operationId") UUID operationId,
            @Valid @RequestBody MaintenanceOperationRequest request) {
        return service.update(userResolver.resolve(headers), operationId, request);
    }

    @PostMapping("/{operationId}/cancel")
    public MaintenanceOperationResponse cancel(
            @RequestHeader HttpHeaders headers,
            @PathVariable("operationId") UUID operationId,
            @Valid @RequestBody(required = false) CancelMaintenanceWindowRequest request) {
        return service.cancel(userResolver.resolve(headers), operationId, request);
    }

    @PostMapping("/{operationId}/finish-now")
    public MaintenanceOperationResponse finishNow(
            @RequestHeader HttpHeaders headers,
            @PathVariable("operationId") UUID operationId) {
        return service.finishNow(userResolver.resolve(headers), operationId);
    }
}
