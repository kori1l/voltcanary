package com.voltcanary.device.controller;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.dto.MaintenanceOutcomeResponse;
import com.voltcanary.device.service.MaintenanceOutcomeService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMaintenanceOutcomeController {

    private final MaintenanceOutcomeService service;
    private final InternalServiceAuth internalServiceAuth;

    @GetMapping("/maintenance-outcomes/changes")
    public List<MaintenanceOutcomeResponse> changes(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @RequestParam(value = "updatedAfter", required = false) Instant updatedAfter,
            @RequestParam(value = "afterId", required = false) UUID afterId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        internalServiceAuth.require(internalToken);
        return service.changes(updatedAfter, afterId, limit);
    }
}
