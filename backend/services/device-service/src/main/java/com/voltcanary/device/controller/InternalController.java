package com.voltcanary.device.controller;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.dto.AccountLifecycleRequest;
import com.voltcanary.device.dto.AccountLifecycleStatusResponse;
import com.voltcanary.device.service.InternalDeviceQueries;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class InternalController {

    private final InternalDeviceQueries useCase;

    public InternalController(InternalDeviceQueries useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/device-deletions/{deviceId}")
    public Map<String, Object> deletion(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
        @PathVariable("deviceId") UUID deviceId
    ) {
        return useCase.deletion(token, deviceId);
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return useCase.ping();
    }

    @GetMapping("/devices/{deviceId}/access")
    public Map<String, Boolean> deviceAccess(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
        @PathVariable("deviceId") UUID deviceId,
        @RequestParam("userId") UUID userId
    ) {
        return useCase.deviceAccess(internalToken, deviceId, userId);
    }

    @GetMapping("/devices/{deviceId}/owner")
    public Map<String, String> deviceOwner(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
        @PathVariable("deviceId") UUID deviceId
    ) {
        return useCase.deviceOwner(internalToken, deviceId);
    }

    @GetMapping("/devices/{deviceId}/metadata")
    public Map<String, String> deviceMetadata(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
        @PathVariable("deviceId") UUID deviceId
    ) {
        return useCase.deviceMetadata(internalToken, deviceId);
    }

    @PostMapping("/accounts/users/{userId}/lifecycle")
    public Map<String, String> syncAccountLifecycle(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
        @PathVariable("userId") UUID userId,
        @Valid @RequestBody AccountLifecycleRequest request
    ) {
        return useCase.syncAccountLifecycle(internalToken, userId, request);
    }

    @GetMapping("/accounts/users/{userId}/lifecycle")
    public AccountLifecycleStatusResponse accountLifecycle(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
        @PathVariable("userId") UUID userId
    ) {
        return useCase.accountLifecycle(internalToken, userId);
    }
}
