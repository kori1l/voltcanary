package com.voltcanary.alert.controller;

import com.voltcanary.alert.dto.AlertRuleResponse;
import com.voltcanary.alert.dto.AlertRuleTestRequest;
import com.voltcanary.alert.dto.AlertRuleTestResponse;
import com.voltcanary.alert.dto.CreateAlertRuleRequest;
import com.voltcanary.alert.dto.UpdateAlertRuleRequest;
import com.voltcanary.alert.service.AlertRuleService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alert-rules")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid alert rule request or filter"),
        @ApiResponse(responseCode = "401", description = "Missing authenticated user context"),
        @ApiResponse(responseCode = "404", description = "Alert rule was not found in the caller account scope")
})
public class AlertRuleController {

    private final AlertRuleService service;

    public AlertRuleController(AlertRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<AlertRuleResponse> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "ruleType", required = false) String ruleType,
            @RequestParam(value = "scopeType", required = false) String scopeType,
            @RequestParam(value = "metric", required = false) String metric) {
        return service.list(userId, accountId, enabled, ruleType, scopeType, metric);
    }

    @GetMapping("/effective")
    public List<AlertRuleResponse> effective(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam("deviceId") UUID deviceId) {
        return service.effectiveForDevice(userId, accountId, deviceId, correlationId);
    }

    @PostMapping("/test")
    public AlertRuleTestResponse test(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody(required = false) AlertRuleTestRequest request) {
        return service.test(userId, accountId, correlationId, request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRuleResponse create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestBody(required = false) CreateAlertRuleRequest request) {
        return service.create(userId, accountId, request);
    }

    @GetMapping("/{id}")
    public AlertRuleResponse get(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id) {
        return service.get(id, userId, accountId);
    }

    @PutMapping("/{id}")
    public AlertRuleResponse update(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id,
            @RequestBody(required = false) UpdateAlertRuleRequest request) {
        return service.update(id, userId, accountId, request);
    }

    @PostMapping("/{id}/enable")
    public AlertRuleResponse enable(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id) {
        return service.setEnabled(id, userId, accountId, true);
    }

    @PostMapping("/{id}/disable")
    public AlertRuleResponse disable(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id) {
        return service.setEnabled(id, userId, accountId, false);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id) {
        service.delete(id, userId, accountId);
    }
}
