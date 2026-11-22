package com.voltcanary.analytics.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.adapter.DashboardReadAuthorization;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.voltcanary.analytics.repository.DashboardAnalyticsRepository;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DashboardEvidenceReads {

    private final DashboardReadAuthorization access;
    private final DashboardAnalyticsRepository repository;
    private final String serviceToken;

    public DashboardEvidenceReads(
        DashboardReadAuthorization access,
        DashboardAnalyticsRepository repository,
        AnalyticsInternalProperties properties
    ) {
        this.access = access;
        this.repository = repository;
        this.serviceToken = properties.getServiceToken();
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
}
