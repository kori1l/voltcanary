package com.voltcanary.device.controller;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.dto.SensorRequest;
import com.voltcanary.device.dto.SensorResponse;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.SensorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/devices/{deviceId}/sensors")
public class SensorController {

    private final SensorService sensorService;
    private final AuthenticatedUserResolver userResolver;

    public SensorController(SensorService sensorService, AuthenticatedUserResolver userResolver) {
        this.sensorService = sensorService;
        this.userResolver = userResolver;
    }

    @GetMapping
    public List<SensorResponse> list(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId) {
        return sensorService.list(userResolver.resolve(headers), deviceId);
    }

    @GetMapping("/{sensorId}")
    public SensorResponse get(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("sensorId") UUID sensorId) {
        return sensorService.get(userResolver.resolve(headers), deviceId, sensorId);
    }

    @PostMapping
    public SensorResponse create(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody SensorRequest request) {
        return sensorService.create(userResolver.resolve(headers), deviceId, request);
    }

    @PatchMapping("/{sensorId}")
    public SensorResponse update(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("sensorId") UUID sensorId,
            @Valid @RequestBody SensorRequest request) {
        return sensorService.update(userResolver.resolve(headers), deviceId, sensorId, request);
    }

    @DeleteMapping("/{sensorId}")
    public void delete(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("sensorId") UUID sensorId) {
        sensorService.delete(userResolver.resolve(headers), deviceId, sensorId);
    }
}
