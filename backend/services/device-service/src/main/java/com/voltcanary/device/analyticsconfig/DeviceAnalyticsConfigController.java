package com.voltcanary.device.analyticsconfig;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices/{deviceId}/analytics-config")
public class DeviceAnalyticsConfigController {

    @GetMapping
    public Map<String, String> get(@PathVariable UUID deviceId) {
        return Map.of("message", "get analytics config endpoint is not implemented yet");
    }

    @PatchMapping
    public Map<String, String> update(@PathVariable UUID deviceId, @RequestBody Map<String, Object> request) {
        return Map.of("message", "update analytics config endpoint is not implemented yet");
    }
}
