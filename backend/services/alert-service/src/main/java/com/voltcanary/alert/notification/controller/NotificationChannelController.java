package com.voltcanary.alert.notification.controller;

import com.voltcanary.alert.notification.application.NotificationChannelService;
import com.voltcanary.alert.notification.application.NotificationDeliveryOperationsService;
import com.voltcanary.alert.notification.dto.NotificationAttemptResponse;
import com.voltcanary.alert.notification.dto.NotificationChannelDiagnosticsResponse;
import com.voltcanary.alert.notification.dto.NotificationChannelRequest;
import com.voltcanary.alert.notification.dto.NotificationChannelResponse;
import com.voltcanary.alert.notification.dto.NotificationTestDeliveryRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alerts/notification-channels")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid channel request, account scope, or secret-like config key"),
        @ApiResponse(responseCode = "401", description = "Missing authenticated user context"),
        @ApiResponse(responseCode = "404", description = "Notification channel was not found in the caller account scope")
})
public class NotificationChannelController {

    private final NotificationChannelService service;
    private final NotificationDeliveryOperationsService operationsService;

    public NotificationChannelController(
            NotificationChannelService service,
            NotificationDeliveryOperationsService operationsService) {
        this.service = service;
        this.operationsService = operationsService;
    }

    @GetMapping
    public List<NotificationChannelResponse> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId) {
        return service.list(userId, accountId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationChannelResponse create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestBody(required = false) NotificationChannelRequest request) {
        return service.create(userId, accountId, request);
    }

    @PutMapping("/{id}")
    public NotificationChannelResponse update(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id,
            @RequestBody(required = false) NotificationChannelRequest request) {
        return service.update(id, userId, accountId, request);
    }

    @PostMapping("/{id}/enable")
    public NotificationChannelResponse enable(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id) {
        return service.setEnabled(id, userId, accountId, true);
    }

    @PostMapping("/{id}/disable")
    public NotificationChannelResponse disable(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id) {
        return service.setEnabled(id, userId, accountId, false);
    }

    @PostMapping("/{id}/test")
    public NotificationAttemptResponse testDelivery(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id,
            @RequestBody(required = false) NotificationTestDeliveryRequest request) {
        return operationsService.testDelivery(id, userId, accountId, request);
    }

    @GetMapping("/{id}/diagnostics")
    public NotificationChannelDiagnosticsResponse diagnostics(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @PathVariable("id") UUID id) {
        return operationsService.diagnostics(id, userId, accountId);
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
