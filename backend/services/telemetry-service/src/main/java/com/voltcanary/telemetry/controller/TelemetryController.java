package com.voltcanary.telemetry.controller;

import com.voltcanary.telemetry.dto.TelemetryQualityResponse;
import com.voltcanary.telemetry.dto.TelemetryReadingResponse;
import com.voltcanary.telemetry.service.TelemetryQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/telemetry/devices/{deviceId}")
public class TelemetryController {

    private final TelemetryQueryService telemetryQueryService;

    public TelemetryController(TelemetryQueryService telemetryQueryService) {
        this.telemetryQueryService = telemetryQueryService;
    }

    @GetMapping("/readings")
    public List<TelemetryReadingResponse> readings(@PathVariable UUID deviceId) {
        return telemetryQueryService.getReadings(deviceId);
    }

    @GetMapping("/latest")
    public TelemetryReadingResponse latest(@PathVariable UUID deviceId) {
        return telemetryQueryService.getLatest(deviceId).orElse(null);
    }

    @GetMapping("/quality")
    public List<TelemetryQualityResponse> quality(@PathVariable UUID deviceId) {
        return telemetryQueryService.getQuality(deviceId);
    }
}
