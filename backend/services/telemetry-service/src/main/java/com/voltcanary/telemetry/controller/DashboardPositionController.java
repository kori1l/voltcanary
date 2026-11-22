package com.voltcanary.telemetry.controller;

import com.voltcanary.common.web.DashboardPositionReadRequest;
import com.voltcanary.telemetry.adapter.DashboardReadAuthorization;
import com.voltcanary.telemetry.adapter.PositionSourceAccess;
import com.voltcanary.telemetry.service.PositionReads;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class DashboardPositionController {

    private final DashboardReadAuthorization access;
    private final PositionSourceAccess sources;
    private final PositionReads reads;

    @PostMapping("/internal/dashboard/positions")
    public Object read(
        @RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody DashboardPositionReadRequest request
    ) {
        if (request != null && request.scope() != null && request.vehicleSources() != null) {
            var targets = request.vehicleSources();
            if (
                request.deviceId() != null ||
                request.sourceId() != null ||
                targets.isEmpty() ||
                targets.size() > 20 ||
                request.scope().deviceIds() == null ||
                !targets.keySet().equals(java.util.Set.copyOf(request.scope().deviceIds())) ||
                targets
                    .values()
                    .stream()
                    .anyMatch(source -> source == null || source.isBlank() || source.length() > 255)
            ) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Fleet position targets must match scope"
                );
            }
            var allowed = access.authorize(token, authorization, request.scope());
            if (!allowed.containsAll(targets.keySet())) {
                throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Fleet position access denied"
                );
            }
            targets.forEach(sources::require);
            var time = request.scope().time();
            return reads.fleetEvidence(targets, time.from(), time.to(), time.asOf());
        }
        if (
            request == null ||
            request.scope() == null ||
            request.deviceId() == null ||
            request.scope().deviceIds().size() != 1 ||
            !request.scope().deviceIds().contains(request.deviceId())
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Exactly one position device is required"
            );
        }
        if (!access.authorize(token, authorization, request.scope()).contains(request.deviceId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Position resource access denied"
            );
        }
        sources.require(request.deviceId(), request.sourceId());
        var time = request.scope().time();
        return reads.evidence(
            request.deviceId(),
            request.sourceId(),
            time.from(),
            time.to(),
            time.asOf()
        );
    }
}
