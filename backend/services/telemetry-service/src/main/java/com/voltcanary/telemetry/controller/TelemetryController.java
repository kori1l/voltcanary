package com.voltcanary.telemetry.controller;

import com.voltcanary.telemetry.dto.TelemetryChartResponse;
import com.voltcanary.telemetry.dto.TelemetryLatestResponse;
import com.voltcanary.telemetry.dto.TelemetryQualityResponse;
import com.voltcanary.telemetry.service.TelemetryQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/telemetry/devices/{deviceId}")
public class TelemetryController {

    private final TelemetryQueryService telemetryQueryService;

    public TelemetryController(TelemetryQueryService telemetryQueryService) {
        this.telemetryQueryService = telemetryQueryService;
    }

    @GetMapping("/readings")
    public TelemetryChartResponse readings(
            @PathVariable("deviceId") UUID deviceId,
            @RequestParam("metric") String metric,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to) {
        Instant effectiveFrom = from == null ? Instant.EPOCH : from;
        Instant effectiveTo = to == null ? Instant.now() : to;
        return telemetryQueryService.chart(deviceId, metric, effectiveFrom, effectiveTo);
    }

    @GetMapping("/latest")
    public TelemetryLatestResponse latest(@PathVariable("deviceId") UUID deviceId) {
        return telemetryQueryService.latestMeasurements(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No telemetry for device"));
    }

    @GetMapping("/quality")
    public List<TelemetryQualityResponse> quality(@PathVariable("deviceId") UUID deviceId) {
        return telemetryQueryService.getQuality(deviceId);
    }
}
