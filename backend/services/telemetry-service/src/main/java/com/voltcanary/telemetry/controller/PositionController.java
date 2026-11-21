package com.voltcanary.telemetry.controller;

import com.voltcanary.telemetry.adapter.PositionSourceAccess;
import com.voltcanary.telemetry.dto.PositionEvidence;
import com.voltcanary.telemetry.service.PositionReads;
import com.voltcanary.telemetry.service.TelemetryAccessService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/telemetry/devices/{deviceId}/positions")
@RequiredArgsConstructor
public class PositionController {
    private final PositionReads reads;
    private final TelemetryAccessService access;
    private final PositionSourceAccess sources;

    @GetMapping("/latest")
    public PositionEvidence.Latest latest(@PathVariable("deviceId") UUID deviceId, @RequestParam("sourceId") String sourceId,
            @RequestParam(value = "asOf", required = false) Instant asOf,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        access.requireReadAccess(deviceId, user, null, correlation);
        sources.require(deviceId, sourceId);
        return reads.latest(deviceId, sourceId, asOf == null ? Instant.now() : asOf);
    }

    @GetMapping
    public PositionEvidence.History history(@PathVariable("deviceId") UUID deviceId, @RequestParam("sourceId") String sourceId,
            @RequestParam("from") Instant from, @RequestParam("to") Instant to,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        access.requireReadAccess(deviceId, user, null, correlation);
        sources.require(deviceId, sourceId);
        return reads.history(deviceId, sourceId, from, to);
    }

    @GetMapping("/recordings")
    public PositionEvidence.Recording recordings(@PathVariable("deviceId") UUID deviceId, @RequestParam("sourceId") String sourceId,
            @RequestParam("from") Instant from, @RequestParam("to") Instant to,
            @RequestParam(value = "cursor", required = false) Instant cursor,
            @RequestParam(value = "limit", defaultValue = "1000") int limit,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        access.requireReadAccess(deviceId, user, null, correlation);
        sources.require(deviceId, sourceId);
        return reads.recordings(deviceId, sourceId, from, to, cursor, limit);
    }
}
