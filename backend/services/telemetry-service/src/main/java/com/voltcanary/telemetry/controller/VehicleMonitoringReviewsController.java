package com.voltcanary.telemetry.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.telemetry.service.TelemetryAccessService;
import com.voltcanary.telemetry.vehicle.ReviewedVehicleMonitoring;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/telemetry/devices/{deviceId}/vehicle-monitoring")
@RequiredArgsConstructor
public class VehicleMonitoringReviewsController {
    private final TelemetryAccessService access;
    private final ReviewedVehicleMonitoring monitoring;
    private final ObjectMapper mapper;

    @GetMapping("/snapshot")
    public ReviewedVehicleMonitoring.Snapshot current(@PathVariable("deviceId") UUID device, @RequestHeader("X-User-Id") UUID user) {
        authorize(device, user);
        return monitoring.current(user, device);
    }
    @PostMapping("/reviews")
    public ReviewedVehicleMonitoring.Review prepare(@PathVariable("deviceId") UUID device, @RequestHeader("X-User-Id") UUID user,
            @RequestBody JsonNode body) {
        authorize(device, user);
        var settings = body.path("settings");
        if (!body.path("expectedRevision").isIntegralNumber() || !body.path("expectedRevision").canConvertToLong()
                || !settings.path("speedLimitKmh").isNumber() || !settings.path("idleMinutes").isIntegralNumber()
                || !settings.path("zones").isArray()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected typed monitoring numbers and zones");
        for (var zone : settings.path("zones")) if (!zone.path("latitude").isNumber() || !zone.path("longitude").isNumber()
                || !zone.path("radiusMetres").isNumber()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected numeric zone geometry");
        return monitoring.prepare(user, device, typed(body, ReviewedVehicleMonitoring.Proposal.class));
    }
    @GetMapping("/reviews/{reviewId}")
    public ReviewedVehicleMonitoring.Review inspect(@PathVariable("deviceId") UUID device, @PathVariable("reviewId") UUID review,
            @RequestHeader("X-User-Id") UUID user) {
        authorize(device, user);
        return monitoring.inspect(user, device, review);
    }
    @PostMapping("/reviews/{reviewId}/confirm")
    public ReviewedVehicleMonitoring.Review confirm(@PathVariable("deviceId") UUID device, @PathVariable("reviewId") UUID review,
            @RequestHeader("X-User-Id") UUID user, @RequestBody JsonNode body) {
        authorize(device, user);
        return monitoring.confirm(user, device, review, typed(body, ReviewedVehicleMonitoring.Confirmation.class));
    }
    private void authorize(UUID device, UUID user) { access.requireReadAccess(device, user.toString(), null, null); }
    private <T> T typed(JsonNode body, Class<T> type) {
        try {
            return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(body);
        } catch (java.io.IOException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected exact typed monitoring review fields", invalid);
        }
    }
}
