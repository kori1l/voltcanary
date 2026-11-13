package com.voltcanary.simulator.controller;

import com.voltcanary.common.web.DeviceDeletionAuthorization;
import com.voltcanary.simulator.service.DeviceHistoryDeletion;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeviceHistoryDeletionController {
    private final DeviceDeletionAuthorization authorization;
    private final DeviceHistoryDeletion history;

    public DeviceHistoryDeletionController(DeviceHistoryDeletion history, DeviceDeletionAuthorization authorization) {
        this.history = history;
        this.authorization = authorization;
    }

    @PostMapping("/internal/device-deletions/{deviceId}")
    public Map<String, String> remove(@PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String token) {
        var deletion = authorization.requireDeletion(deviceId, token);
        history.close(deviceId, false);
        if (deletion.purge()) {
            history.close(deviceId, true);
        }
        return Map.of("status", "COMPLETED");
    }
}
