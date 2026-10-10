package com.voltcanary.dashboard.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.config.DashboardReadProperties;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardEvidenceReads;
import com.voltcanary.dashboard.query.QueryTypes.Scope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

@Component
public class HttpDashboardEvidenceReads implements DashboardEvidenceReads {
    private final DashboardReadProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpDashboardEvidenceReads(DashboardReadProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    @Override
    public DashboardActor activeActor(String authorization, java.util.UUID userId) {
        JsonNode response = read(properties.deviceBaseUrl(), "/internal/dashboard/scope", authorization,
                mapper.createObjectNode().put("mode", "UNBOUND"));
        if (!userId.toString().equals(response.path("userId").asText()) || !response.path("devices").isArray()
                || !response.path("devices").isEmpty() || response.path("evaluatedCount").asLong(-1) != 0)
            throw failed("INVALID_READ_RESPONSE", "Invalid active actor scope response");
        try {
            return new DashboardActor(userId, java.util.UUID.fromString(response.path("accountId").asText()));
        } catch (IllegalArgumentException invalid) {
            throw failed("INVALID_READ_RESPONSE", "Invalid active account identifier");
        }
    }

    @Override
    public ScopeSnapshot scope(String authorization, DashboardActor actor, Scope scope) {
        JsonNode response = read(properties.deviceBaseUrl(), "/internal/dashboard/scope", authorization, mapper.valueToTree(scope));
        ScopeSnapshot snapshot;
        try {
            snapshot = mapper.convertValue(response, ScopeSnapshot.class);
        } catch (IllegalArgumentException exception) {
            throw failed("INVALID_READ_RESPONSE", "Invalid device scope response");
        }
        if (!actor.userId().equals(snapshot.userId()) || !actor.accountId().equals(snapshot.accountId())
                || snapshot.devices() == null || snapshot.devices().size() > 500
                || snapshot.devices().size() != snapshot.evaluatedCount()
                || snapshot.requestedCount() < snapshot.evaluatedCount()) {
            throw failed("INVALID_READ_RESPONSE", "Device scope ownership/count mismatch");
        }
        return snapshot;
    }

    @Override
    public JsonNode telemetry(String authorization, DashboardActor actor, ReadRequest request) {
        return read(properties.telemetryBaseUrl(), "/internal/dashboard/telemetry", authorization, requestBody(actor, request));
    }

    @Override
    public JsonNode energy(String authorization,DashboardActor actor,ReadRequest request) {
        return read(properties.telemetryBaseUrl(),"/internal/dashboard/energy",authorization,energyBody(actor,request));
    }

    @Override
    public JsonNode energyValidation(String authorization,DashboardActor actor,ReadRequest request) {
        return read(properties.telemetryBaseUrl(),"/internal/dashboard/energy-validation",authorization,energyBody(actor,request));
    }

    private JsonNode energyBody(DashboardActor actor,ReadRequest request) {
        var body=mapper.createObjectNode().put("independentMetersConfirmed",request.independentMetersConfirmed());
        var scope=(com.fasterxml.jackson.databind.node.ObjectNode)requestBody(actor,request);
        scope.remove("independentMetersConfirmed");
        return body.set("scope",scope);
    }

    @Override
    public JsonNode analytics(String authorization, DashboardActor actor, ReadRequest request) {
        return read(properties.analyticsBaseUrl(), "/internal/dashboard/analytics", authorization, requestBody(actor, request));
    }

    @Override
    public JsonNode incidents(String authorization, DashboardActor actor, ReadRequest request) {
        return read(properties.alertBaseUrl(), "/internal/dashboard/incidents", authorization, requestBody(actor, request));
    }

    @Override
    public JsonNode maintenance(String authorization, DashboardActor actor, ReadRequest request) {
        return read(properties.deviceBaseUrl(), "/internal/dashboard/maintenance", authorization, requestBody(actor, request));
    }

    private JsonNode requestBody(DashboardActor actor, ReadRequest request) {
        var body = mapper.valueToTree(request);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("userId", actor.userId().toString());
        return body;
    }

    private JsonNode read(URI base, String path, String authorization, JsonNode body) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(properties.timeout())
                .header("Authorization", authorization).header("X-Internal-Service-Token", properties.internalServiceToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw failed("NO_ACCESS", "Current resource access is unavailable");
            }
            if (response.statusCode() == 422) {
                if (path.startsWith("/internal/dashboard/energy")) throw failed("VALIDATION_ERROR","Review distinct physical meters and their independence; use a supported non-overlapping scope and period");
                throw failed("LIMIT_EXCEEDED", "Typed owner read requires a narrower supported scope");
            }
            if (response.statusCode() != 200) {
                throw failed("DEPENDENCY_UNAVAILABLE", "Evidence owner is unavailable");
            }
            JsonNode result = mapper.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw failed("INVALID_READ_RESPONSE", "Invalid evidence owner response");
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failed("READ_TIMEOUT", "Evidence read was interrupted or exceeded its budget");
        } catch (IOException exception) {
            throw failed("DEPENDENCY_UNAVAILABLE", "Evidence owner is unavailable");
        }
    }

    private DashboardFailure failed(String code, String message) {
        return new DashboardFailure(code, message);
    }
}
