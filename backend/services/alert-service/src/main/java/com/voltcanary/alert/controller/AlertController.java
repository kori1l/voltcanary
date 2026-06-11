package com.voltcanary.alert.controller;

import com.voltcanary.alert.dto.AlertActionResponse;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.dto.AlertListFilters;
import com.voltcanary.alert.dto.AlertPageResponse;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.dto.AlertSummaryResponse;
import com.voltcanary.alert.dto.NotificationAttemptResponse;
import com.voltcanary.alert.dto.ResolveAlertRequest;
import com.voltcanary.alert.service.AlertService;
import com.voltcanary.alert.service.NotificationAttemptService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alerts")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid filter, UUID, enum, pagination, or request body value"),
        @ApiResponse(responseCode = "401", description = "Missing authenticated user context"),
        @ApiResponse(responseCode = "403", description = "Authenticated user lacks the required role"),
        @ApiResponse(responseCode = "404", description = "Alert was not found or is outside the caller scope"),
        @ApiResponse(responseCode = "503", description = "Device authorization service is unavailable")
})
public class AlertController {

    private final AlertService alertService;
    private final NotificationAttemptService notificationAttemptService;

    public AlertController(AlertService alertService, NotificationAttemptService notificationAttemptService) {
        this.alertService = alertService;
        this.notificationAttemptService = notificationAttemptService;
    }

    @GetMapping
    public List<AlertResponse> listAlerts(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "deviceId", required = false) UUID deviceId,
            @RequestParam(value = "status", required = false) AlertStatus status,
            @RequestParam(value = "severity", required = false) AlertSeverity severity,
            @RequestParam(value = "alertType", required = false) String alertType,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return alertService.listAlerts(
                userId,
                role,
                correlationId,
                new AlertListFilters(deviceId, status, severity, alertType, from, to, limit));
    }

    @GetMapping("/summary")
    public AlertSummaryResponse alertSummary(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "deviceId", required = false) UUID deviceId,
            @RequestParam(value = "status", required = false) AlertStatus status,
            @RequestParam(value = "severity", required = false) AlertSeverity severity,
            @RequestParam(value = "alertType", required = false) String alertType,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to) {
        return alertService.alertSummary(
                userId,
                role,
                correlationId,
                new AlertListFilters(deviceId, status, severity, alertType, from, to, null));
    }

    @GetMapping("/page")
    public AlertPageResponse pageAlerts(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam(value = "deviceId", required = false) UUID deviceId,
            @RequestParam(value = "status", required = false) AlertStatus status,
            @RequestParam(value = "severity", required = false) AlertSeverity severity,
            @RequestParam(value = "alertType", required = false) String alertType,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return alertService.pageAlerts(
                userId,
                role,
                correlationId,
                new AlertListFilters(deviceId, status, severity, alertType, from, to, null),
                page,
                size);
    }

    @GetMapping("/support/search")
    public AlertPageResponse supportSearchAlerts(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestParam(value = "accountId", required = false) UUID accountId,
            @RequestParam(value = "deviceId", required = false) UUID deviceId,
            @RequestParam(value = "status", required = false) AlertStatus status,
            @RequestParam(value = "severity", required = false) AlertSeverity severity,
            @RequestParam(value = "alertType", required = false) String alertType,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return alertService.supportSearchAlerts(
                userId,
                role,
                new AlertListFilters(deviceId, status, severity, alertType, from, to, null),
                accountId,
                page,
                size);
    }

    @GetMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
    public AlertResponse getAlert(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable("id") UUID id) {
        return alertService.getAlert(id, userId, role, correlationId);
    }

    @GetMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/notification-attempts")
    public List<NotificationAttemptResponse> notificationAttempts(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable("id") UUID id) {
        return notificationAttemptService.listForAlert(id, userId, role, correlationId);
    }

    @PostMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/ack")
    public AlertActionResponse acknowledge(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable("id") UUID id) {
        AlertResponse alert = alertService.acknowledge(id, userId, role, correlationId);
        return new AlertActionResponse(alert.status(), alert.id());
    }

    @PostMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/resolve")
    public AlertActionResponse resolve(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable("id") UUID id,
            @RequestBody(required = false) ResolveAlertRequest request) {
        AlertResponse alert = alertService.resolve(id, request == null ? null : request.reason(), userId, role, correlationId);
        return new AlertActionResponse(alert.status(), alert.id());
    }
}
