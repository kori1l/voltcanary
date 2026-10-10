package com.voltcanary.device.controller;

import com.voltcanary.common.web.EnergyMeterDefinition;
import com.voltcanary.device.dto.EnergyMeterRequest;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.EnergyMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EnergyMeterController {
    private final EnergyMeterRegistry registry;
    private final AuthenticatedUserResolver users;

    public EnergyMeterController(EnergyMeterRegistry registry,AuthenticatedUserResolver users) {
        this.registry = registry;
        this.users = users;
    }

    @GetMapping("/devices/{deviceId}/energy-meter")
    public List<EnergyMeterDefinition> read(@RequestHeader HttpHeaders headers,@PathVariable("deviceId") UUID deviceId) {
        return registry.read(users.resolve(headers),deviceId);
    }

    @PutMapping("/devices/{deviceId}/energy-meter")
    public EnergyMeterDefinition save(@RequestHeader HttpHeaders headers,@PathVariable("deviceId") UUID deviceId,
            @RequestBody EnergyMeterRequest request) {
        return registry.save(users.resolve(headers),deviceId,request);
    }
}
