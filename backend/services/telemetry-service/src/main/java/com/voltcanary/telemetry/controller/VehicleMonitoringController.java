package com.voltcanary.telemetry.controller;

import com.voltcanary.telemetry.service.TelemetryAccessService;
import com.voltcanary.telemetry.vehicle.VehicleMonitoringRepository;
import com.voltcanary.telemetry.vehicle.VehicleMonitoringSettings;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/telemetry/devices/{deviceId}/vehicle-monitoring")
public class VehicleMonitoringController {
    private final TelemetryAccessService access;
    private final VehicleMonitoringRepository repository;
    public VehicleMonitoringController(TelemetryAccessService access, VehicleMonitoringRepository repository) {
        this.access = access;
        this.repository = repository;
    }
    @GetMapping
    public VehicleMonitoringSettings get(@PathVariable("deviceId") UUID device, @RequestHeader("X-User-Id") String user) {
        access.requireReadAccess(device, user, null, null);
        return repository.get(UUID.fromString(user), device);
    }
    @PutMapping
    public VehicleMonitoringSettings save(@PathVariable("deviceId") UUID device, @RequestHeader("X-User-Id") String user, @RequestBody VehicleMonitoringSettings settings) {
        access.requireReadAccess(device, user, null, null);
        return repository.save(UUID.fromString(user), device, settings);
    }
}
