package com.voltcanary.telemetry.controller;

import com.voltcanary.telemetry.service.TelemetryAccessService;
import com.voltcanary.telemetry.service.TelemetryFieldReads;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TelemetryFieldsController {
    private final TelemetryAccessService access;
    private final TelemetryFieldReads reads;
    public TelemetryFieldsController(TelemetryAccessService access, TelemetryFieldReads reads) {
        this.access = access;
        this.reads = reads;
    }
    @GetMapping("/telemetry/devices/{deviceId}/fields")
    public List<TelemetryFieldReads.Field> latest(@PathVariable("deviceId") UUID deviceId,
            @RequestParam(value="sourceId", required=false) String sourceId,
            @RequestHeader(value="X-User-Id", required=false) String user,
            @RequestHeader(value="X-User-Role", required=false) String role,
            @RequestHeader(value="X-Correlation-Id", required=false) String correlation,
            @RequestHeader(value="X-Internal-Service-Token", required=false) String token) {
        access.requireReadAccess(deviceId, user, role, correlation, token);
        return reads.latest(deviceId, sourceId);
    }
}
