package com.voltcanary.device.service;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.dto.AccountLifecycleRequest;
import com.voltcanary.device.dto.AccountLifecycleStatusResponse;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.DeviceTypeRepository;
import com.voltcanary.device.service.AccountService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class InternalDeviceQueries {

    private final DeviceRepository deviceRepository;
    private final DeviceTypeRepository deviceTypeRepository;
    private final AccountService accountService;
    private final InternalServiceAuth internalServiceAuth;
    private final com.voltcanary.device.service.DeviceDeletionService deletions;

    public Map<String, Object> deletion(String token, UUID deviceId) {
        internalServiceAuth.require(token);
        return deletions.internalReceipt(deviceId);
    }

    public Map<String, String> ping() {
        return Map.of("status", "ok", "service", "device-service");
    }

    public Map<String, Boolean> deviceAccess(String internalToken, UUID deviceId, UUID userId) {
        internalServiceAuth.require(internalToken);
        return Map.of(
            "allowed",
            deviceRepository.existsByIdAndAccountUserId(deviceId, userId) ||
                deletions.retainedAccess(deviceId, userId)
        );
    }

    public Map<String, String> deviceOwner(String internalToken, UUID deviceId) {
        internalServiceAuth.require(internalToken);
        Device device = deviceRepository
            .findById(deviceId)
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found")
            );
        return Map.of(
            "deviceId",
            device.getId().toString(),
            "accountId",
            device.getAccountId().toString()
        );
    }

    public Map<String, String> deviceMetadata(String internalToken, UUID deviceId) {
        internalServiceAuth.require(internalToken);
        Device device = deviceRepository
            .findById(deviceId)
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found")
            );
        String deviceType = deviceTypeRepository
            .findById(device.getDeviceTypeId())
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Device type not found")
            )
            .getCode();
        Map<String, String> response = new LinkedHashMap<>();
        response.put("deviceId", device.getId().toString());
        response.put("accountId", device.getAccountId().toString());
        if (device.getLocationId() != null) {
            response.put("locationId", device.getLocationId().toString());
        }
        response.put("type", deviceType);
        response.put("status", device.getStatus().name());
        return response;
    }

    public Map<String, String> syncAccountLifecycle(
        String internalToken,
        UUID userId,
        @Valid AccountLifecycleRequest request
    ) {
        internalServiceAuth.require(internalToken);
        var account = accountService.syncUser(userId, request.email(), request.status());
        return Map.of(
            "accountId",
            account.getId().toString(),
            "status",
            account.getStatus().name()
        );
    }

    public AccountLifecycleStatusResponse accountLifecycle(String internalToken, UUID userId) {
        internalServiceAuth.require(internalToken);
        Account account = accountService
            .findByUserId(userId)
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")
            );
        return new AccountLifecycleStatusResponse(
            account.getUserId(),
            account.getId(),
            account.getStatus().name()
        );
    }
}
