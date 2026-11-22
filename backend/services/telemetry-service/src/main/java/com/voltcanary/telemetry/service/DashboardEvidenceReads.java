package com.voltcanary.telemetry.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.telemetry.adapter.DashboardReadAuthorization;
import com.voltcanary.telemetry.config.TelemetryAuthorizationProperties;
import com.voltcanary.telemetry.repository.DashboardTelemetryRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DashboardEvidenceReads {

    private final DashboardReadAuthorization access;
    private final DashboardTelemetryRepository repository;
    private final String serviceToken;
    private final TelemetryQueryService queries;

    public DashboardEvidenceReads(
        DashboardReadAuthorization access,
        DashboardTelemetryRepository repository,
        TelemetryAuthorizationProperties properties,
        TelemetryQueryService queries
    ) {
        this.access = access;
        this.repository = repository;
        this.serviceToken = properties.getInternalServiceToken();
        this.queries = queries;
    }

    public ObjectNode read(String token, String authorization, DashboardOwnerReadRequest request) {
        return repository.read(request, access.authorize(token, authorization, request));
    }

    public ObjectNode workspace(
        String authorization,
        UUID workspace,
        DashboardOwnerReadRequest request
    ) {
        if (request == null || !workspace.equals(request.workspaceId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Explicit workspace context required"
            );
        }
        return read(serviceToken, authorization, request);
    }

    public java.util.List<com.voltcanary.telemetry.dto.TelemetryMetricResponse> metrics(
        String authorization,
        UUID user,
        UUID workspace,
        UUID device,
        UUID account,
        java.time.Instant from,
        java.time.Instant to
    ) {
        var request = new DashboardOwnerReadRequest(
            user,
            account,
            java.util.List.of(device),
            new DashboardOwnerReadRequest.TimeFrame(from, to, java.time.Instant.now(), null, null),
            java.util.Set.of(),
            java.util.List.of(),
            1,
            0,
            "ACTIVE",
            java.util.List.of(),
            workspace
        );
        if (!access.authorize(serviceToken, authorization, request).contains(device)) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Current telemetry capability unavailable"
            );
        }
        return queries.getMetrics(device, from, to);
    }
}
