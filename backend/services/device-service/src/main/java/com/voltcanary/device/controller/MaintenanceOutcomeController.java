package com.voltcanary.device.controller;

import com.voltcanary.device.dto.MaintenanceOutcomeRequest;
import com.voltcanary.device.dto.MaintenanceOutcomeResponse;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.MaintenanceOutcomeService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class MaintenanceOutcomeController {

    private final MaintenanceOutcomeService service;
    private final AuthenticatedUserResolver userResolver;

    public MaintenanceOutcomeController(MaintenanceOutcomeService service, AuthenticatedUserResolver userResolver) {
        this.service = service;
        this.userResolver = userResolver;
    }

    @GetMapping("/maintenance-operations/{operationId}/outcomes")
    public List<MaintenanceOutcomeResponse> list(
            @RequestHeader HttpHeaders headers,
            @PathVariable("operationId") UUID operationId) {
        return service.list(userResolver.resolve(headers), operationId);
    }

    @PostMapping("/maintenance-operations/{operationId}/outcomes")
    public MaintenanceOutcomeResponse create(
            @RequestHeader HttpHeaders headers,
            @PathVariable("operationId") UUID operationId,
            @Valid @RequestBody MaintenanceOutcomeRequest request) {
        return service.create(userResolver.resolve(headers), operationId, request);
    }

    @GetMapping("/maintenance-outcomes/{outcomeId}")
    public MaintenanceOutcomeResponse get(
            @RequestHeader HttpHeaders headers,
            @PathVariable("outcomeId") UUID outcomeId) {
        return service.get(userResolver.resolve(headers), outcomeId);
    }

    @PatchMapping("/maintenance-outcomes/{outcomeId}")
    public MaintenanceOutcomeResponse update(
            @RequestHeader HttpHeaders headers,
            @PathVariable("outcomeId") UUID outcomeId,
            @Valid @RequestBody MaintenanceOutcomeRequest request) {
        return service.update(userResolver.resolve(headers), outcomeId, request);
    }
}
