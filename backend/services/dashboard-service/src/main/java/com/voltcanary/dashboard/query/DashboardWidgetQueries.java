package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.application.DashboardAccess;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardDefinition.Widget;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardEvidenceReads;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ReadRequest;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ScopeSnapshot;
import com.voltcanary.dashboard.port.DashboardStorage;
import com.voltcanary.dashboard.query.QueryTypes.Batch;
import com.voltcanary.dashboard.query.QueryTypes.Config;
import com.voltcanary.dashboard.query.QueryTypes.Frame;
import com.voltcanary.dashboard.query.QueryTypes.Manifest;
import com.voltcanary.dashboard.query.QueryTypes.Panel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class DashboardWidgetQueries {
    public record Request(Integer revision, UUID draftEtag, JsonNode runtimeContext, boolean forceRefresh, List<String> widgetIds) {
        public Request(Integer revision, UUID draftEtag, JsonNode runtimeContext, boolean forceRefresh) {
            this(revision, draftEtag, runtimeContext, forceRefresh, null);
        }
    }
    private record Prepared(Widget widget, Config config, ScopeSnapshot scope, Frame frame, DashboardFailure failure) { }
    private record Cached(DashboardActor actor, Instant until, Batch batch) { }
    private final QueryDefinitionReader definitions;
    private final WidgetManifests manifests;
    private final WidgetQueryDecoder decoder;
    private final WidgetScopeResolver scopes;
    private final QueryTimeResolver time;
    private final QueryReadScheduler scheduler;
    private final WidgetEvidenceProjection projection;
    private final ObjectMapper mapper;
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    public DashboardWidgetQueries(QueryDefinitionReader definitions, WidgetManifests manifests,
            WidgetQueryDecoder decoder, WidgetScopeResolver scopes, QueryTimeResolver time,
            QueryReadScheduler scheduler, WidgetEvidenceProjection projection, ObjectMapper mapper) {
        this.definitions = definitions;
        this.manifests = manifests;
        this.decoder = decoder;
        this.scopes = scopes;
        this.time = time;
        this.scheduler = scheduler;
        this.projection = projection;
        this.mapper = mapper;
    }

    public Batch query(String authorization, DashboardActor actor, UUID id, Request request) {
        return query(authorization, actor, id, request, false);
    }
    public Batch query(String authorization, DashboardActor actor, UUID id, Request request, boolean validateOnly) {
        var resolved = definitions.read(actor, id, request);
        DashboardDefinition definition = resolved.definition();
        int revision = resolved.revision();
        var runtime = decoder.runtime(request.runtimeContext());
        var defaults = decoder.runtime(definition.defaultContext());
        Instant asOf = Instant.now();
        List<Prepared> prepared = new ArrayList<>();
        Map<QueryTypes.Scope, ScopeSnapshot> scopeMemo = new HashMap<>();
        JsonNode capabilities = null;
        for (Widget widget : definition.widgets()) {
            if (request.widgetIds() != null && !request.widgetIds().contains(widget.id())) continue;
            var manifest = manifests.find(widget.type(), widget.typeVersion());
            if (manifest.isEmpty() || !manifest.get().supported()) {
                prepared.add(new Prepared(widget, null, null, null, new DashboardFailure("UNSUPPORTED_WIDGET", "Widget version or dependency is unavailable")));
                continue;
            }
            try {
                Config config = decoder.config(widget, manifest.get());
                ScopeSnapshot scope = scopes.resolve(authorization, actor, defaults.scope(), runtime.scope(), decoder.scope(widget.binding()), scopeMemo);
                if (scope.deniedBindingCount() > 0) throw new DashboardFailure("NO_ACCESS", "Resource binding is no longer accessible");
                if (manifest.get().maxDetailDevices() > 0 && scope.evaluatedCount() > manifest.get().maxDetailDevices())
                    throw new DashboardFailure("LIMIT_EXCEEDED", "Detail scope exceeds the widget device limit");
                if ("latest-value".equals(widget.type()) && (scope.evaluatedCount() != 1 || config.metric() == null))
                    throw new DashboardFailure("VALIDATION_ERROR", "Latest value requires one device and a metric");
                if ("metric-summary".equals(widget.type()) && config.metric() == null)
                    throw new DashboardFailure("VALIDATION_ERROR", "Summary requires a metric");
                if ("time-series".equals(widget.type()) && config.series().isEmpty())
                    throw new DashboardFailure("VALIDATION_ERROR", "Select at least one typed series");
                if ("context-note".equals(widget.type()) && widget.payload() != null && widget.payload().path("text").asText().length() > 1000)
                    throw new DashboardFailure("LIMIT_EXCEEDED", "Notes are limited to 1000 characters");
                Frame frame = time.resolve(request.runtimeContext() == null || !request.runtimeContext().hasNonNull("time")
                        ? defaults.time() : runtime.time(), config.time(), config.compare(), asOf);
                if (!"maintenance-agenda".equals(widget.type()) && frame.to().isAfter(asOf))
                    throw new DashboardFailure("VALIDATION_ERROR", "Future intervals are available only for maintenance");
                var allowed = scope.devices().stream().map(DashboardEvidenceReads.Device::id).collect(java.util.stream.Collectors.toSet());
                if (config.series().stream().anyMatch(series -> !allowed.contains(series.deviceId())))
                    throw new DashboardFailure("NO_ACCESS", "Series binding is outside the resolved scope");
                if (validateOnly && List.of("latest-value", "metric-summary", "time-series").contains(widget.type())) {
                    if (capabilities == null) capabilities = scheduler.read("telemetry",
                            new ReadRequest(List.of(), actor.accountId(), frame, Set.of(), List.of(), 1, 0, "ACTIVE", List.of()),
                            authorization, actor, new HashMap<>()).get(5, TimeUnit.SECONDS);
                    projection.registered(config, capabilities);
                }
                prepared.add(new Prepared(widget, config, scope, frame, null));
            } catch (DashboardFailure failure) {
                if ("LIMIT_EXCEEDED".equals(failure.code())) throw failure;
                prepared.add(new Prepared(widget, null, null, null, failure));
            }
            catch (Exception failure) { prepared.add(new Prepared(widget, null, null, null,
                    new DashboardFailure("DEPENDENCY_UNAVAILABLE", "Capability owner is unavailable"))); }
        }
        // Authorization and source metadata were renewed above, before any cache lookup.
        List<Object> fingerprint = new ArrayList<>();
        for (Prepared panel : prepared) fingerprint.add(panel.scope() == null ? panel.failure().code()
                : List.of(panel.widget().id(), panel.scope().devices(), panel.scope().locations()));
        if (validateOnly) {
            String hash = QueryHash.of(mapper, List.of(actor, id, revision, fingerprint, asOf));
            return new Batch(id, revision, request.draftEtag(), hash, asOf, Instant.now(), false, prepared.stream()
                    .map(panel -> panel.failure() != null ? failed(panel, hash, panel.failure())
                            : new Panel(panel.widget().id(), "valid", null, hash, panel.frame(),
                                    Math.toIntExact(panel.scope().requestedCount()), Math.toIntExact(panel.scope().evaluatedCount()),
                                    Instant.now(), null, mapper.createObjectNode().put("validationOnly", true), null, null)).toList());
        }
        String key = QueryHash.of(mapper, List.of(actor, id, revision, request.draftEtag() == null ? "published" : request.draftEtag(),
                definition, runtime, fingerprint));
        boolean safe = prepared.stream().noneMatch(panel -> panel.failure() != null);
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> entry.getValue().until().isBefore(asOf));
            if (safe && !request.forceRefresh()) {
                Cached hit = cache.get(key);
                if (hit != null) {
                    Batch batch = hit.batch();
                    return new Batch(id, batch.revision(), batch.draftEtag(), batch.contextHash(), batch.asOf(), Instant.now(), true, batch.panels());
                }
            }
            if (!safe) cache.entrySet().removeIf(entry -> entry.getValue().actor().equals(actor));
        }
        String contextHash = QueryHash.of(mapper, List.of(key, asOf));
        Map<QueryReadScheduler.Key, CompletableFuture<JsonNode>> reads = new HashMap<>();
        List<Map<String, CompletableFuture<JsonNode>>> pending = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (Prepared panel : prepared) {
            Map<String, CompletableFuture<JsonNode>> owners = new LinkedHashMap<>();
            if (panel.failure() == null) {
                ReadRequest read = readRequest(actor, panel);
                for (String owner : projection.owners(panel.widget().type())) {
                    try { owners.put(owner, scheduler.read(owner, read, authorization, actor, reads)); }
                    catch (java.util.concurrent.RejectedExecutionException rejected) {
                        owners.put(owner, CompletableFuture.failedFuture(new DashboardFailure("DEPENDENCY_UNAVAILABLE", "Read concurrency budget is busy")));
                    }
                }
            }
            pending.add(owners);
        }
        List<Panel> panels = new ArrayList<>();
        for (int index = 0; index < prepared.size(); index++) {
            Prepared panel = prepared.get(index);
            if (panel.failure() != null) { panels.add(failed(panel, contextHash, panel.failure())); continue; }
            try {
                Map<String, JsonNode> evidence = new LinkedHashMap<>();
                for (var source : pending.get(index).entrySet()) {
                    long left = deadline - System.nanoTime();
                    JsonNode result = source.getValue().isDone() ? source.getValue().join()
                            : source.getValue().get(Math.max(1, left), TimeUnit.NANOSECONDS);
                    evidence.put(source.getKey(), result);
                }
                ObjectNode data = projection.project(panel.widget(), panel.config(), panel.frame(), panel.scope(), evidence);
                ObjectNode provenance = mapper.createObjectNode().put("consistency", "OWNER_READ_SNAPSHOTS_SHARED_QUERY_ASOF")
                        .put("readOnly", true).put("cachedSnapshotMaxAgeSeconds", 30);
                evidence.forEach((owner, value) -> {
                    ObjectNode metadata = mapper.createObjectNode();
                    for (String field : List.of("queryAt", "asOf", "intervalBasis", "weighting", "maintenancePolicy", "gapPolicy", "source", "timeBasis", "listTimeBasis", "confidenceMeaning", "riskMeaning"))
                        if (value.has(field)) metadata.set(field, value.get(field));
                    provenance.set(owner, metadata);
                });
                String status = noData(panel, data, evidence) ? "no_data" : "ready";
                panels.add(new Panel(panel.widget().id(), status, data, contextHash, panel.frame(),
                        Math.toIntExact(panel.scope().requestedCount()), Math.toIntExact(panel.scope().evaluatedCount()),
                        Instant.now(), measuredAt(panel, data, evidence), provenance, null, null));
            } catch (Exception exception) {
                Throwable cause = exception;
                while (cause.getCause() != null && !(cause instanceof DashboardFailure)) cause = cause.getCause();
                DashboardFailure failure = cause instanceof DashboardFailure typed ? typed
                        : new DashboardFailure(exception instanceof java.util.concurrent.TimeoutException ? "READ_TIMEOUT" : "DEPENDENCY_UNAVAILABLE", "Owner evidence is unavailable");
                panels.add(failed(panel, contextHash, failure));
            }
        }
        Batch batch = new Batch(id, revision, request.draftEtag(), contextHash, asOf, Instant.now(), false, List.copyOf(panels));
        reads.values().forEach(future -> {
            if (!future.isDone()) future.cancel(false);
        });
        if (panels.stream().anyMatch(panel -> "no_access".equals(panel.status()))) synchronized (cache) {
            cache.entrySet().removeIf(entry -> entry.getValue().actor().equals(actor));
        }
        if (safe && panels.stream().allMatch(panel -> "ready".equals(panel.status()) || "no_data".equals(panel.status()))
                && mapper.valueToTree(batch).toString().length() < 2_000_000) synchronized (cache) {
            while (cache.size() >= 64) cache.remove(cache.keySet().iterator().next());
            cache.put(key, new Cached(actor, asOf.plusSeconds(30), batch));
        }
        return batch;
    }

    private ReadRequest readRequest(DashboardActor actor, Prepared panel) {
        Config config = panel.config();
        List<UUID> ids = panel.scope().devices().stream().map(DashboardEvidenceReads.Device::id).sorted().toList();
        Set<String> metrics = new java.util.TreeSet<>();
        if (config.metric() != null) metrics.add(config.metric());
        config.series().forEach(series -> metrics.add(series.metric()));
        return new ReadRequest(ids, actor.accountId(), panel.frame(), metrics,
                manifests.find(panel.widget().type(), panel.widget().typeVersion()).orElseThrow().maxDetailDevices() > 0 ? ids : List.of(),
                config.rows(), config.offset(), config.incidentMode() == null ? "ACTIVE" : config.incidentMode().name(), config.series());
    }
    private Panel failed(Prepared panel, String hash, DashboardFailure failure) {
        String status = switch (failure.code()) { case "NO_ACCESS" -> "no_access"; case "UNSUPPORTED_WIDGET" -> "unsupported";
            case "VALIDATION_ERROR", "LIMIT_EXCEEDED" -> "invalid"; default -> "unavailable"; };
        return new Panel(panel.widget().id(), status, null, hash, panel.frame(), 0, 0, Instant.now(), null,
                mapper.createObjectNode().put("readOnly", true), failure.code(), failure.getMessage());
    }
    private Instant measuredAt(Prepared panel, JsonNode data, Map<String, JsonNode> evidence) {
        if ("metric-summary".equals(panel.widget().type())) return parseTime(data.path("summary").path("measuredAt"));
        if ("latest-value".equals(panel.widget().type())) return parseTime(data.path("value").path("measuredAt"));
        if ("time-series".equals(panel.widget().type())) {
            Instant latest = null;
            for (JsonNode series : data.path("series")) for (JsonNode bucket : series.path("buckets")) {
                Instant at = parseTime(bucket.path("lastAt"));
                if (at != null && (latest == null || at.isAfter(latest))) latest = at;
            }
            return latest;
        }
        Instant latest = null;
        for (JsonNode source : evidence.values()) for (String key : List.of("latest", "summaries", "risks", "predictions"))
            for (JsonNode row : source.path(key)) if (row.hasNonNull("measuredAt")) {
                Instant at = Instant.parse(row.get("measuredAt").asText());
                if (latest == null || at.isAfter(latest)) latest = at;
            }
        return latest;
    }
    private Instant parseTime(JsonNode value) {
        return value.isTextual() ? Instant.parse(value.asText()) : null;
    }
    private boolean noData(Prepared panel, JsonNode data, Map<String, JsonNode> evidence) {
        if ("context-note".equals(panel.widget().type())) return false;
        if (panel.scope().devices().isEmpty()) return true;
        return switch (panel.widget().type()) {
            case "latest-value" -> data.path("value").isNull();
            case "metric-summary" -> data.path("summary").isNull();
            case "time-series" -> !data.path("series").isArray() || data.path("series").isEmpty()
                    || java.util.stream.StreamSupport.stream(data.path("series").spliterator(), false).allMatch(series -> series.path("buckets").isEmpty());
            case "risk-list", "prediction-evidence" -> data.path("total").asInt() == 0;
            case "incident-list" -> evidence.get("incidents").path("total").asLong(-1) == 0;
            case "maintenance-agenda" -> evidence.get("maintenance").path("total").asLong(-1) == 0;
            default -> false;
        };
    }
}
