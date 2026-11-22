package com.voltcanary.device.controller;

import com.voltcanary.common.web.VehiclePositionTargets;
import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.service.PositionSourceReads;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalPositionSourceController {

    private final PositionSourceReads useCase;

    public InternalPositionSourceController(PositionSourceReads useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/internal/devices/{deviceId}/position-source")
    public Map<String, String> source(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
        @PathVariable("deviceId") UUID deviceId,
        @RequestParam("sourceId") String sourceId
    ) {
        return useCase.source(token, deviceId, sourceId);
    }

    @PostMapping("/internal/vehicles/position-sources")
    public Map<String, Boolean> fleet(
        @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
        @RequestBody VehiclePositionTargets request
    ) {
        return useCase.fleet(token, request);
    }
}
