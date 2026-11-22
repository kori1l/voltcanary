package com.voltcanary.telemetry.controller;

import com.voltcanary.common.web.VehiclePositionTargets;
import com.voltcanary.telemetry.adapter.PositionSourceAccess;
import com.voltcanary.telemetry.dto.PositionEvidence;
import com.voltcanary.telemetry.service.PositionReads;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class VehicleFleetController {
    private final PositionReads reads;
    private final PositionSourceAccess sources;

    public record Request(List<VehiclePositionTargets.Target> targets, Instant asOf) { }

    @PostMapping("/telemetry/vehicles/positions")
    public List<PositionEvidence.Latest> latest(@RequestBody Request request,
            @RequestHeader(value = "X-User-Id", required = false) String user,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        if (request.targets() == null || request.targets().isEmpty() || request.targets().size() > 100
                || request.targets().stream().anyMatch(target -> target == null || target.deviceId() == null || target.sourceId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fleet requires 1–100 bound targets");
        }
        sources.requireFleet(user, request.targets());
        Instant asOf = request.asOf() == null ? Instant.now() : request.asOf();
        return request.targets().stream().map(target -> reads.latest(target.deviceId(), target.sourceId(), asOf)).toList();
    }
}
