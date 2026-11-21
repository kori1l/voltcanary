package com.voltcanary.device.service;

import com.voltcanary.device.dto.DeviceRequest;
import com.voltcanary.device.dto.DeviceResponse;
import com.voltcanary.device.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DeviceCreation {
    private final AccountService accounts;
    private final DeviceService devices;
    private final DeviceCreationTransaction receipts;

    public DeviceResponse create(AuthenticatedUser actor, DeviceRequest request, String key) {
        if (key == null) return devices.create(actor, request);
        if (key.isBlank() || key.length() > 160 || !key.equals(key.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency key must contain 1–160 characters without surrounding spaces");
        }
        // Preserve ordinary first-use account provisioning outside the receipt transaction.
        try { accounts.currentAccount(actor); }
        catch (DataIntegrityViolationException concurrentProvisioning) {
            if (accounts.findByUserId(actor.id()).isEmpty()) throw concurrentProvisioning;
        }
        return receipts.create(actor, accounts.currentAccount(actor).getId(), request, key);
    }
}
