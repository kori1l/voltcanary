package com.voltcanary.device.analyticsconfig;

import com.voltcanary.device.security.AuthenticatedUserResolver;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices/{deviceId}/analytics-config")
public class DeviceAnalyticsConfigController {

    private final DeviceAnalyticsConfigService service;
    private final AuthenticatedUserResolver userResolver;

    public DeviceAnalyticsConfigController(DeviceAnalyticsConfigService service, AuthenticatedUserResolver userResolver) {
        this.service = service;
        this.userResolver = userResolver;
    }

    @GetMapping
    public DeviceAnalyticsConfigResponse get(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId) {
        return service.get(userResolver.resolve(headers), deviceId);
    }

    @PatchMapping
    public DeviceAnalyticsConfigResponse update(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody DeviceAnalyticsConfigRequest request) {
        return service.update(userResolver.resolve(headers), deviceId, request);
    }
}
