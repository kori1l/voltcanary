package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.dto.MlSettingsRequest;
import com.voltcanary.analytics.dto.MlSettingsResponse;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.MlSettingsService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/analytics/ml-settings")
public class MlSettingsController {
    private final MlSettingsService service;
    private final AnalyticsAccessService access;
    public MlSettingsController(MlSettingsService service, AnalyticsAccessService access) { this.service = service; this.access = access; }

    @GetMapping public MlSettingsResponse account(
            @RequestHeader(value="X-Account-Id", required=false) UUID accountId,
            @RequestHeader(value="X-User-Id", required=false) String userId,
            @RequestHeader(value="X-Correlation-Id", required=false) String correlationId) {
        return service.account(resolveAccountId(accountId, userId, correlationId));
    }
    @PutMapping public MlSettingsResponse updateAccount(@RequestHeader(value="X-Account-Id", required=false) UUID accountId,
            @RequestHeader(value="X-User-Id", required=false) String userId,
            @RequestHeader(value="X-User-Role", required=false) String role,
            @RequestHeader(value="X-Correlation-Id", required=false) String correlationId, @Valid @RequestBody MlSettingsRequest body) {
        requireWrite(role); return service.saveAccount(resolveAccountId(accountId, userId, correlationId), uuid(userId), body);
    }
    @GetMapping("/devices/{deviceId}") public MlSettingsResponse device(@PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value="X-Account-Id", required=false) UUID accountId, @RequestHeader(value="X-User-Id", required=false) String userId,
            @RequestHeader(value="X-User-Role", required=false) String role, @RequestHeader(value="X-Correlation-Id", required=false) String correlationId) {
        access.requireReadAccess(deviceId, userId, role, correlationId); return service.device(resolveDeviceAccountId(accountId, deviceId, correlationId), deviceId);
    }
    @PutMapping("/devices/{deviceId}") public MlSettingsResponse updateDevice(@PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value="X-Account-Id", required=false) UUID accountId, @RequestHeader(value="X-User-Id", required=false) String userId,
            @RequestHeader(value="X-User-Role", required=false) String role, @RequestHeader(value="X-Correlation-Id", required=false) String correlationId,
            @Valid @RequestBody MlSettingsRequest body) {
        requireWrite(role); access.requireReadAccess(deviceId, userId, role, correlationId);
        return service.saveDevice(resolveDeviceAccountId(accountId, deviceId, correlationId), deviceId, uuid(userId), body);
    }
    @DeleteMapping("/devices/{deviceId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearDevice(@PathVariable("deviceId") UUID deviceId, @RequestHeader(value="X-Account-Id", required=false) UUID accountId,
            @RequestHeader(value="X-User-Id", required=false) String userId, @RequestHeader(value="X-User-Role", required=false) String role,
            @RequestHeader(value="X-Correlation-Id", required=false) String correlationId) {
        requireWrite(role); access.requireReadAccess(deviceId, userId, role, correlationId); service.clearDevice(resolveDeviceAccountId(accountId, deviceId, correlationId), deviceId);
    }
    private void requireWrite(String role) {
        if (!("ADMIN".equals(role) || "OWNER".equals(role) || "USER".equals(role))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ML settings write access denied");
    }
    private UUID uuid(String value) {
        try { return UUID.fromString(value); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user"); }
    }
    private UUID resolveAccountId(UUID accountId, String userId, String correlationId) {
        return accountId != null ? accountId : access.accountIdForUser(userId, correlationId);
    }
    private UUID resolveDeviceAccountId(UUID accountId, UUID deviceId, String correlationId) {
        return accountId != null ? accountId : access.accountIdForDevice(deviceId, correlationId);
    }
}
