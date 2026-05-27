package com.voltcanary.telemetry.controller;

import com.voltcanary.telemetry.config.TelemetryValidationProperties;
import com.voltcanary.telemetry.dto.TelemetryChartResponse;
import com.voltcanary.telemetry.dto.TelemetryExportResponse;
import com.voltcanary.telemetry.dto.TelemetryLatestResponse;
import com.voltcanary.telemetry.dto.TelemetryMetricResponse;
import com.voltcanary.telemetry.dto.TelemetryQualityResponse;
import com.voltcanary.telemetry.service.TelemetryAccessService;
import com.voltcanary.telemetry.service.TelemetryQueryService;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final TelemetryAccessService telemetryAccessService;
    private final TelemetryValidationProperties validationProperties;

    public TelemetryController(
            TelemetryQueryService telemetryQueryService,
            TelemetryAccessService telemetryAccessService,
            TelemetryValidationProperties validationProperties) {
        this.telemetryQueryService = telemetryQueryService;
        this.telemetryAccessService = telemetryAccessService;
        this.validationProperties = validationProperties;
    }

    @GetMapping("/readings")
    public TelemetryChartResponse readings(
            @PathVariable("deviceId") UUID deviceId,
            @RequestParam("metric") String metric,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "interval", required = false) String interval,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        telemetryAccessService.requireReadAccess(deviceId, userId, role, correlationId);
        Instant effectiveTo = to == null ? Instant.now() : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(validationProperties.getMaxChartRange()) : from;
        requireAcceptedRange(effectiveFrom, effectiveTo);
        return telemetryQueryService.chart(deviceId, metric, effectiveFrom, effectiveTo, interval);
    }

    @GetMapping("/readings/export")
    public TelemetryExportResponse exportReadings(
            @PathVariable("deviceId") UUID deviceId,
            @RequestParam("metric") String metric,
            @RequestParam("from") Instant from,
            @RequestParam("to") Instant to,
            @RequestParam(value = "cursor", required = false) Instant cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "500") int limit,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        telemetryAccessService.requireReadAccess(deviceId, userId, role, correlationId);
        requireAcceptedRange(from, to);
        int effectiveLimit = Math.max(1, Math.min(limit, validationProperties.getMaxExportPageSize()));
        return telemetryQueryService.export(deviceId, metric, from, to, cursor, effectiveLimit);
    }

    @GetMapping("/latest")
    public TelemetryLatestResponse latest(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        telemetryAccessService.requireReadAccess(deviceId, userId, role, correlationId);
        return telemetryQueryService.latestMeasurements(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No telemetry for device"));
    }

    @GetMapping("/quality")
    public List<TelemetryQualityResponse> quality(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        telemetryAccessService.requireReadAccess(deviceId, userId, role, correlationId);
        return telemetryQueryService.getQuality(deviceId);
    }

    @GetMapping("/metrics")
    public List<TelemetryMetricResponse> metrics(
            @PathVariable("deviceId") UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        telemetryAccessService.requireReadAccess(deviceId, userId, role, correlationId);
        return telemetryQueryService.getMetrics(deviceId);
    }

    private void requireAcceptedRange(Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        Duration range = Duration.between(from, to);
        if (range.compareTo(validationProperties.getMaxChartRange()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Telemetry query range exceeds configured limit");
        }
    }
}
