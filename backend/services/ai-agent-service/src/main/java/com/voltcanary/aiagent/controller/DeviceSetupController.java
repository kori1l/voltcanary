package com.voltcanary.aiagent.controller;

import com.voltcanary.aiagent.security.JwtIdentityService;
import com.voltcanary.aiagent.service.DeviceSetupExecutor;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai-agent/device-setups")
@RequiredArgsConstructor
public class DeviceSetupController {
    private final JwtIdentityService identities;
    private final DeviceSetupExecutor setups;

    @GetMapping("/{deviceId}")
    public Map<?, ?> status(@RequestHeader("Authorization") String authorization, @PathVariable("deviceId") UUID deviceId) {
        identities.parseBearerToken(authorization);
        return setups.status(deviceId, authorization);
    }
}
