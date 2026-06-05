package com.voltcanary.alert.controller;

import com.voltcanary.alert.dto.NotificationPreferenceRequest;
import com.voltcanary.alert.dto.NotificationPreferenceResponse;
import com.voltcanary.alert.service.NotificationPreferenceService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alerts/notification-preferences")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid account scope, timezone, severity, or preference request"),
        @ApiResponse(responseCode = "401", description = "Missing authenticated user context")
})
public class NotificationPreferenceController {

    private final NotificationPreferenceService service;

    public NotificationPreferenceController(NotificationPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public NotificationPreferenceResponse getDefault(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId) {
        return service.getDefault(userId, accountId);
    }

    @PutMapping
    public NotificationPreferenceResponse updateDefault(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Account-Id", required = false) String accountId,
            @RequestBody(required = false) NotificationPreferenceRequest request) {
        return service.updateDefault(userId, accountId, request);
    }
}
