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
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class HttpDashboardEvidenceReads implements DashboardEvidenceReads {

    private final DashboardReadProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final DashboardBusinessReads business;

    public HttpDashboardEvidenceReads(DashboardReadProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.business = new DashboardBusinessReads(mapper);
        this.client = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    @Override
    public DashboardActor activeActor(String authorization, java.util.UUID userId) {
        JsonNode response = read(
            properties.deviceBaseUrl(),
            "/internal/dashboard/scope",
            authorization,
            mapper.createObjectNode().put("mode", "UNBOUND")
        );
        if (
            !userId.toString().equals(response.path("userId").asText()) ||
            !response.path("devices").isArray() ||
            !response.path("devices").isEmpty() ||
            response.path("evaluatedCount").asLong(-1) != 0
        ) throw failed("INVALID_READ_RESPONSE", "Invalid active actor scope response");
        try {
            return new DashboardActor(
                userId,
                java.util.UUID.fromString(response.path("accountId").asText())
            );
        } catch (IllegalArgumentException invalid) {
            throw failed("INVALID_READ_RESPONSE", "Invalid active account identifier");
        }
    }

    @Override
    public ScopeSnapshot scope(String authorization, DashboardActor actor, Scope scope) {
        return scopeForAction(authorization, actor, scope, "device.read");
    }

    @Override
    public ScopeSnapshot scopeForAction(
        String authorization,
        DashboardActor actor,
        Scope scope,
        String action
    ) {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(scope);
        if (scope.workspaceId() != null) body.put("action", action);
        JsonNode response = read(
            properties.deviceBaseUrl(),
            "/internal/dashboard/scope",
            authorization,
            body
        );
        ScopeSnapshot snapshot;
        try {
            snapshot = mapper.convertValue(response, ScopeSnapshot.class);
        } catch (IllegalArgumentException exception) {
            throw failed("INVALID_READ_RESPONSE", "Invalid device scope response");
        }
        if (
            !actor.userId().equals(snapshot.userId()) ||
            !(scope.workspaceId() == null ? actor.accountId() : scope.accountId()).equals(
                snapshot.accountId()
            ) ||
            snapshot.devices() == null ||
            snapshot.devices().size() > 500 ||
            snapshot.devices().size() != snapshot.evaluatedCount() ||
            snapshot.requestedCount() < snapshot.evaluatedCount()
        ) {
            throw failed("INVALID_READ_RESPONSE", "Device scope ownership/count mismatch");
        }
        return snapshot;
    }

    @Override
    public JsonNode positions(String authorization, DashboardActor actor, ReadRequest request) {
        if (request.deviceIds().isEmpty()) {
            var empty = mapper.createObjectNode().put("evaluatedCount", 0);
            empty.putArray("authorizedDeviceIds");
            empty.putObject("positions").putObject("latest").putNull("position");
            return empty;
        }
        var body = mapper
            .createObjectNode()
            .put("deviceId", request.deviceIds().getFirst().toString())
            .put("sourceId", request.sourceId());
        if (request.vehicleSources() != null) {
            body.remove(java.util.List.of("deviceId", "sourceId"));
            var allowedSources = new java.util.TreeMap<UUID, String>();
            request
                .deviceIds()
                .forEach(id -> allowedSources.put(id, request.vehicleSources().get(id)));
            body.set("vehicleSources", mapper.valueToTree(allowedSources));
        }
        body.set("scope", requestBody(actor, request));
        var evidence = read(
            properties.telemetryBaseUrl(),
            "/internal/dashboard/positions",
            authorization,
            body
        );
        var result = mapper.createObjectNode().put("evaluatedCount", request.deviceIds().size());
        result.set("authorizedDeviceIds", mapper.valueToTree(request.deviceIds()));
        return result.set("positions", evidence);
    }

    @Override
    public JsonNode telemetry(String authorization, DashboardActor actor, ReadRequest request) {
        return read(
            properties.telemetryBaseUrl(),
            "/internal/dashboard/telemetry",
            authorization,
            requestBody(actor, request)
        );
    }

    @Override
    public JsonNode energy(String authorization, DashboardActor actor, ReadRequest request) {
        return read(
            properties.telemetryBaseUrl(),
            "/internal/dashboard/energy",
            authorization,
            energyBody(actor, request)
        );
    }

    @Override
    public JsonNode energyValidation(
        String authorization,
        DashboardActor actor,
        ReadRequest request
    ) {
        return read(
            properties.telemetryBaseUrl(),
            "/internal/dashboard/energy-validation",
            authorization,
            energyBody(actor, request)
        );
    }

    private JsonNode energyBody(DashboardActor actor, ReadRequest request) {
        var body = mapper
            .createObjectNode()
            .put("independentMetersConfirmed", request.independentMetersConfirmed());
        var scope = (com.fasterxml.jackson.databind.node.ObjectNode) requestBody(actor, request);
        scope.remove("independentMetersConfirmed");
        return body.set("scope", scope);
    }

    @Override
    public JsonNode analytics(String authorization, DashboardActor actor, ReadRequest request) {
        return read(
            properties.analyticsBaseUrl(),
            "/internal/dashboard/analytics",
            authorization,
            requestBody(actor, request)
        );
    }

    @Override
    public JsonNode incidents(String authorization, DashboardActor actor, ReadRequest request) {
        return read(
            properties.alertBaseUrl(),
            "/internal/dashboard/incidents",
            authorization,
            requestBody(actor, request)
        );
    }

    @Override
    public JsonNode maintenance(String authorization, DashboardActor actor, ReadRequest request) {
        return read(
            properties.deviceBaseUrl(),
            "/internal/dashboard/maintenance",
            authorization,
            requestBody(actor, request)
        );
    }

    private JsonNode requestBody(DashboardActor actor, ReadRequest request) {
        var body = mapper.valueToTree(request);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove(
            java.util.List.of("sourceId", "vehicleSources")
        );
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put(
            "userId",
            actor.userId().toString()
        );
        return body;
    }

    @Override
    public JsonNode cost(String authorization, DashboardActor actor, ReadRequest request) {
        JsonNode tariffs = read(
            properties.deviceBaseUrl(),
            "/internal/dashboard/tariffs",
            authorization,
            requestBody(actor, request)
        );
        qualifiedBusinessSource(request, tariffs);
        return business.batched(
            energy -> business.cost(request, tariffs, energy),
            intervals -> energyBatch(authorization, actor, request, intervals)
        );
    }

    @Override
    public JsonNode businessVersion(
        String authorization,
        DashboardActor actor,
        ReadRequest request,
        String owner
    ) {
        String path = "cost".equals(owner)
            ? "/internal/dashboard/tariffs"
            : "/internal/dashboard/operations";
        String field = "cost".equals(owner) ? "tariffs" : "operations";
        var source = read(
            properties.deviceBaseUrl(),
            path,
            authorization,
            requestBody(actor, request)
        );
        qualifiedBusinessSource(request, source);
        var fingerprint = mapper.createObjectNode().set("current", source.path(field));
        if ("operations".equals(owner) && request.time().compareFrom() != null) {
            var previous = business.interval(
                request,
                request.time().compareFrom(),
                request.time().compareTo()
            );
            var baseline = read(
                properties.deviceBaseUrl(),
                path,
                authorization,
                requestBody(actor, previous)
            );
            qualifiedBusinessSource(previous, baseline);
            ((com.fasterxml.jackson.databind.node.ObjectNode) fingerprint).set(
                "baseline",
                baseline.path(field)
            );
        }
        return fingerprint;
    }

    @Override
    public JsonNode operations(String authorization, DashboardActor actor, ReadRequest request) {
        JsonNode facts = read(
            properties.deviceBaseUrl(),
            "/internal/dashboard/operations",
            authorization,
            requestBody(actor, request)
        );
        qualifiedBusinessSource(request, facts);
        var result = business.batched(
            energy -> business.operations(request, facts, energy),
            intervals -> energyBatch(authorization, actor, request, intervals)
        );
        if (request.time().compareFrom() != null && request.time().compareTo() != null) {
            var previousRequest = business.interval(
                request,
                request.time().compareFrom(),
                request.time().compareTo()
            );
            var previousFacts = read(
                properties.deviceBaseUrl(),
                "/internal/dashboard/operations",
                authorization,
                requestBody(actor, previousRequest)
            );
            qualifiedBusinessSource(previousRequest, previousFacts);
            var baseline = business.batched(
                energy -> business.operations(previousRequest, previousFacts, energy),
                intervals -> energyBatch(authorization, actor, previousRequest, intervals)
            );
            result.set("baseline", baseline.path("operations"));
            for (var current : result.path("operations"))
                for (var previous : baseline.path("operations"))
                    if (current.path("deviceId").equals(previous.path("deviceId"))) {
                        boolean comparable =
                            "COMPLETE".equals(current.path("uptime").path("coverage").asText()) &&
                            "COMPLETE".equals(previous.path("uptime").path("coverage").asText()) &&
                            current
                                .path("uptime")
                                .path("observedSeconds")
                                .equals(previous.path("uptime").path("observedSeconds"));
                        ((com.fasterxml.jackson.databind.node.ObjectNode) current).set(
                            "uptimeComparison",
                            mapper.valueToTree(
                                com.voltcanary.dashboard.query.QueryTypes.Comparison.of(
                                    comparable
                                        ? current.path("uptime").path("uptimeRatio").asDouble()
                                        : null,
                                    comparable
                                        ? previous.path("uptime").path("uptimeRatio").asDouble()
                                        : null
                                )
                            )
                        );
                        boolean cycles =
                            "COMPLETE".equals(current.path("cycleCoverage").asText()) &&
                            "COMPLETE".equals(previous.path("cycleCoverage").asText());
                        ((com.fasterxml.jackson.databind.node.ObjectNode) current).set(
                            "cycleComparison",
                            mapper.valueToTree(
                                com.voltcanary.dashboard.query.QueryTypes.Comparison.of(
                                    cycles ? current.path("energyPerCycleKWh").asDouble() : null,
                                    cycles ? previous.path("energyPerCycleKWh").asDouble() : null
                                )
                            )
                        );
                    }
        }
        return result;
    }

    @Override
    public JsonNode nativeRows(String authorization, JsonNode document) {
        var dataset = com.voltcanary.dashboard.query.NativeDashboardRows.validate(document);
        return read(
            properties.deviceBaseUrl(),
            "/data/datasets/" + dataset + "/query",
            authorization,
            mapper.createObjectNode().set("document", document)
        );
    }

    @Override
    public JsonNode ruleMeasure(String authorization, DashboardActor actor, JsonNode selection) {
        var query = com.voltcanary.dashboard.query.DashboardRuleMeasure.parse(selection);
        var body = mapper
            .createObjectNode()
            .put("account", actor.accountId().toString())
            .put("epoch", query.epoch().toString())
            .put("measure", query.measure().toString());
        return read(
            properties.analyticsBaseUrl(),
            "/analytics/configurable/rule-cutovers/historical-measure",
            authorization,
            body,
            actor.userId()
        );
    }

    private JsonNode read(URI base, String path, String authorization, JsonNode body) {
        return read(base, path, authorization, body, null);
    }

    private JsonNode read(URI base, String path, String authorization, JsonNode body, UUID actor) {
        var builder = HttpRequest.newBuilder(base.resolve(path))
            .timeout(properties.timeout())
            .header("Authorization", authorization)
            .header("X-Internal-Service-Token", properties.internalServiceToken())
            .header("Content-Type", "application/json");
        if (actor != null) builder.header("X-User-Id", actor.toString());
        HttpRequest request = builder
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        try {
            HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw failed("NO_ACCESS", "Current resource access is unavailable");
            }
            if (path.startsWith("/data/datasets/") && response.statusCode() == 404) {
                throw failed("NO_ACCESS", "The selected resource is unavailable");
            }
            if (
                path.endsWith("/historical-measure") &&
                (response.statusCode() == 404 || response.statusCode() == 410)
            ) {
                throw failed("MEASURE_UNAVAILABLE", "The saved rule measure is unavailable");
            }
            if (
                path.startsWith("/data/datasets/") &&
                (response.statusCode() == 400 || response.statusCode() == 409)
            ) {
                throw failed("VALIDATION_ERROR", "Review the table schema and selected fields");
            }
            if (response.statusCode() == 422) {
                if (path.startsWith("/internal/dashboard/energy")) throw failed(
                    "VALIDATION_ERROR",
                    "Review distinct physical meters and their independence; use a supported non-overlapping scope and period"
                );
                throw failed(
                    "LIMIT_EXCEEDED",
                    "Typed owner read requires a narrower supported scope"
                );
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

    private java.util.List<JsonNode> energyBatch(
        String authorization,
        DashboardActor actor,
        ReadRequest scope,
        java.util.List<ReadRequest> intervals
    ) {
        if (intervals.size() > 64) throw failed(
            "LIMIT_EXCEEDED",
            "At most64 energy intervals are supported"
        );
        var body = mapper.createObjectNode().set("scope", requestBody(actor, scope));
        var requests = ((com.fasterxml.jackson.databind.node.ObjectNode) body).putArray(
            "intervals"
        );
        for (int i = 0; i < intervals.size(); i++) {
            var interval = intervals.get(i);
            var item = requests
                .addObject()
                .put("key", Integer.toString(i))
                .put("from", interval.time().from().toString())
                .put("to", interval.time().to().toString())
                .put("independentMetersConfirmed", interval.independentMetersConfirmed());
            item.set("deviceIds", mapper.valueToTree(interval.deviceIds()));
        }
        var response = read(
            properties.telemetryBaseUrl(),
            "/internal/dashboard/energy-batch",
            authorization,
            body
        );
        if (
            !scope.time().asOf().toString().equals(response.path("asOf").asText()) ||
            response.path("intervals").size() != intervals.size()
        ) throw failed("INVALID_READ_RESPONSE", "Energy batch watermark or interval count differs");
        var results = new java.util.ArrayList<JsonNode>();
        for (int i = 0; i < intervals.size(); i++) results.add(
            response.path("intervals").path(Integer.toString(i))
        );
        return results;
    }

    private void qualifiedBusinessSource(ReadRequest request, JsonNode source) {
        if (source.path("deniedBindingCount").asLong() > 0) throw failed(
            "NO_ACCESS",
            "Business input scope changed"
        );
        var ids = new java.util.HashSet<String>();
        source.path("authorizedDeviceIds").forEach(id -> ids.add(id.asText()));
        var expected = request
            .deviceIds()
            .stream()
            .map(Object::toString)
            .collect(java.util.stream.Collectors.toSet());
        if (
            !source.path("authorizedDeviceIds").isArray() ||
            source.path("authorizedDeviceIds").size() != ids.size() ||
            !ids.equals(expected) ||
            source.path("evaluatedCount").asInt(-1) != expected.size()
        ) throw failed("INVALID_READ_RESPONSE", "Business input scope differs");
    }

    private DashboardFailure failed(String code, String message) {
        return new DashboardFailure(code, message);
    }
}
