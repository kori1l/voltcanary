package com.voltcanary.device.controller;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.account.AccountLifecycleRequest;
import com.voltcanary.device.account.AccountService;
import com.voltcanary.device.device.DeviceRepository;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class InternalController {

    private final DeviceRepository deviceRepository;
    private final AccountService accountService;
    private final InternalServiceAuth internalServiceAuth;

    public InternalController(
            DeviceRepository deviceRepository,
            AccountService accountService,
            InternalServiceAuth internalServiceAuth) {
        this.deviceRepository = deviceRepository;
        this.accountService = accountService;
        this.internalServiceAuth = internalServiceAuth;
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "service", "device-service");
    }

    @GetMapping("/devices/{deviceId}/access")
    public Map<String, Boolean> deviceAccess(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @PathVariable("deviceId") UUID deviceId,
            @RequestParam("userId") UUID userId) {
        internalServiceAuth.require(internalToken);
        return Map.of("allowed", deviceRepository.existsByIdAndAccountUserId(deviceId, userId));
    }

    @PostMapping("/accounts/users/{userId}/lifecycle")
    public Map<String, String> syncAccountLifecycle(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody AccountLifecycleRequest request) {
        internalServiceAuth.require(internalToken);
        var account = accountService.syncUser(userId, request.email(), request.status());
        return Map.of("accountId", account.getId().toString(), "status", account.getStatus().name());
    }
}
