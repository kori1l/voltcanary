package com.voltcanary.alert.controller;

import com.voltcanary.alert.config.AlertInternalServiceAuth;
import com.voltcanary.alert.dto.AlertRuleSnapshotResponse;
import com.voltcanary.alert.service.AlertRuleService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/alert-rules")
public class InternalAlertRuleController {

    private final AlertRuleService service;
    private final AlertInternalServiceAuth internalServiceAuth;

    public InternalAlertRuleController(AlertRuleService service, AlertInternalServiceAuth internalServiceAuth) {
        this.service = service;
        this.internalServiceAuth = internalServiceAuth;
    }

    @GetMapping("/effective")
    public List<AlertRuleSnapshotResponse> effective(
            @RequestHeader(value = AlertInternalServiceAuth.HEADER, required = false) String internalToken,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam("deviceId") UUID deviceId) {
        internalServiceAuth.require(internalToken);
        return service.effectiveRuleSnapshotsForDevice(deviceId, correlationId);
    }
}
