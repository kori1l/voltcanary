package com.voltcanary.telemetry.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.telemetry.service.TelemetryAccessService;
import com.voltcanary.telemetry.vehicle.VehicleMonitoringRepository;
import com.voltcanary.telemetry.vehicle.VehicleMonitoringSettings;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/telemetry/devices/{deviceId}/vehicle-monitoring")
@RequiredArgsConstructor
public class VehicleMonitoringController {
    private final TelemetryAccessService access;
    private final VehicleMonitoringRepository repository;
    private final ObjectMapper mapper;

    @GetMapping
    public VehicleMonitoringSettings get(@PathVariable("deviceId") UUID device, @RequestHeader("X-User-Id") String user) {
        access.requireReadAccess(device, user, null, null);
        return repository.get(UUID.fromString(user), device);
    }
    @PutMapping
    public VehicleMonitoringSettings save(@PathVariable("deviceId") UUID device, @RequestHeader("X-User-Id") String user, @RequestBody JsonNode body) {
        access.requireReadAccess(device, user, null, null);
        VehicleMonitoringSettings settings;
        try {
            settings = mapper.readerFor(VehicleMonitoringSettings.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(body);
        } catch (java.io.IOException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Expected typed monitoring settings with an integer idleMinutes", invalid);
        }
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Prepare and confirm a monitoring review before saving");
    }
}
