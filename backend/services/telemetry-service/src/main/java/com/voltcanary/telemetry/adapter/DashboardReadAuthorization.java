package com.voltcanary.telemetry.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.telemetry.config.TelemetryAuthorizationProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Mandatory signed-actor and active owner checks for the additive read port. */
@Component
public class DashboardReadAuthorization {
    private final String internalToken;
    private final URI deviceBase;
    private final HttpClient client;
    private final Duration timeout;
    private final ObjectMapper mapper;

    public DashboardReadAuthorization(TelemetryAuthorizationProperties properties, ObjectMapper mapper) {
        this.internalToken = properties.getInternalServiceToken();
        this.deviceBase = URI.create(properties.getDeviceServiceBaseUrl());
        this.timeout = properties.getTimeout();
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.mapper = mapper;
    }

    public List<UUID> authorize(String token, String authorization, DashboardOwnerReadRequest request) {
        if (token == null || authorization == null || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated internal read is required");
        }
        validate(request);
        var body = mapper.createObjectNode().put("mode", request.deviceIds().isEmpty() ? "UNBOUND" : "FIXED");
        body.set("deviceIds", mapper.valueToTree(request.deviceIds()));
        HttpRequest http = HttpRequest.newBuilder(deviceBase.resolve("/internal/dashboard/scope")).timeout(timeout)
                .header("Authorization", authorization).header("X-Internal-Service-Token", internalToken)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current resource access is unavailable");
            }
            if (response.statusCode() != 200) {
                throw unavailable();
            }
            JsonNode scope = mapper.readTree(response.body());
            if (scope == null || !scope.path("devices").isArray() || !scope.path("evaluatedCount").isIntegralNumber()
                    || scope.path("evaluatedCount").asLong() != scope.path("devices").size()) {
                throw unavailable();
            }
            if (!request.userId().toString().equals(scope.path("userId").asText())
                    || !request.accountId().toString().equals(scope.path("accountId").asText())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Read actor does not own the scope");
            }
            List<UUID> allowed = new ArrayList<>();
            for (JsonNode device : scope.path("devices")) {
                UUID id = UUID.fromString(device.path("id").asText());
                if (!request.deviceIds().contains(id) || allowed.contains(id)) {
                    throw unavailable();
                }
                allowed.add(id);
            }
            return List.copyOf(allowed);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private void validate(DashboardOwnerReadRequest request) {
        if (request == null || request.userId() == null || request.accountId() == null
                || request.deviceIds().size() > 500 || request.deviceIds().stream().distinct().count() != request.deviceIds().size()
                || request.detailDeviceIds().size() > 50 || !request.deviceIds().containsAll(request.detailDeviceIds())
                || request.series().size() > 192 || request.series().stream().anyMatch(series -> series.deviceId() == null
                    || series.metric() == null || !request.detailDeviceIds().contains(series.deviceId())
                    || !series.metric().matches("[A-Za-z][A-Za-z0-9_]{0,99}"))
                || request.metrics().size() > 24 || request.metrics().stream().anyMatch(metric -> !metric.matches("[A-Za-z][A-Za-z0-9_]{0,99}"))
                || request.rows() < 1 || request.rows() > 50 || request.offset() < 0 || request.offset() > 1000000
                || request.time() == null || request.time().asOf() == null
                || !range(request.time().from(), request.time().to())
                || (request.time().compareFrom() != null && !range(request.time().compareFrom(), request.time().compareTo()))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid typed owner read or exceeded budget");
        }
    }

    private boolean range(java.time.Instant from, java.time.Instant to) {
        return from != null && to != null && from.isBefore(to) && Duration.between(from, to).compareTo(Duration.ofDays(31)) <= 0;
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Current device scope is unavailable");
    }
}
