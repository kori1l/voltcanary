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
import com.voltcanary.dashboard.query.QueryTypes.ScopeMode;
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
    private record Cached(DashboardActor actor, Instant until, Batch batch, int bytes) { }
    private final QueryDefinitionReader definitions;
    private final WidgetManifests manifests;
    private final WidgetQueryDecoder decoder;
    private final WidgetScopeResolver scopes;
    private final QueryTimeResolver time;
    private final QueryReadScheduler scheduler;
    private final WidgetEvidenceProjection projection;
    private final ObjectMapper mapper;
    private final CompactDashboardEvidence compact;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);

    public DashboardWidgetQueries(QueryDefinitionReader definitions, WidgetManifests manifests,
            WidgetQueryDecoder decoder, WidgetScopeResolver scopes, QueryTimeResolver time,
            QueryReadScheduler scheduler, WidgetEvidenceProjection projection, ObjectMapper mapper) {
        this(definitions, manifests, decoder, scopes, time, scheduler, projection, mapper, new CompactDashboardEvidence(mapper));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DashboardWidgetQueries(QueryDefinitionReader definitions, WidgetManifests manifests,
            WidgetQueryDecoder decoder, WidgetScopeResolver scopes, QueryTimeResolver time,
            QueryReadScheduler scheduler, WidgetEvidenceProjection projection, ObjectMapper mapper, CompactDashboardEvidence compact) {
        this.definitions = definitions;
        this.manifests = manifests;
        this.decoder = decoder;
        this.scopes = scopes;
        this.time = time;
        this.scheduler = scheduler;
        this.projection = projection;
        this.mapper = mapper;
        this.compact = compact;
    }

    public Batch query(String authorization, DashboardActor actor, UUID id, Request request) {
        return query(authorization, actor, id, request, false);
    }
    public Batch query(String authorization, DashboardActor actor, UUID id, Request request, boolean validateOnly) {
        return query(authorization,actor,id,request,validateOnly,null);
    }
    public Batch query(String authorization, DashboardActor actor, UUID id, Request request, boolean validateOnly, java.util.function.Consumer<Batch> progress) {
        var resolved = definitions.read(actor, id, request);
        DashboardDefinition definition = resolved.definition();
        int revision = resolved.revision();
        return queryDefinition(authorization, actor, id, request, validateOnly, progress, definition, revision, false);
    }

    /** Unsaved definitions use the same scope, time, owner reads and evidence projection as saved dashboards. */
    public Batch preview(String authorization, DashboardActor actor, DashboardDefinition definition) {
        UUID id = UUID.nameUUIDFromBytes(QueryHash.of(mapper, List.of("unsaved-preview", actor, definition))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return queryDefinition(authorization, actor, id, new Request(null, null, null, true), false, null, definition, 0, true);
    }

    private Batch queryDefinition(String authorization, DashboardActor actor, UUID id, Request request,
            boolean validateOnly, java.util.function.Consumer<Batch> progress, DashboardDefinition definition,
            int revision, boolean unsaved) {
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
                if (WidgetManifests.configuredEvidence(widget.type())) {
                    if (NativeDashboardRows.TYPE.equals(widget.type())) NativeDashboardRows.validate(widget.query());
                    else DashboardRuleMeasure.parse(widget.query());
                    if (defaults.scope().workspaceId() != null || runtime.scope().workspaceId() != null
                            || decoder.scope(widget.binding()).workspaceId() != null) {
                        throw new DashboardFailure("NO_ACCESS", "Configured evidence requires a private dashboard");
                    }
                    if (validateOnly) {
                        var ownerRead = scheduler.configuredEvidence(authorization, actor, widget);
                        try {
                            projectConfigured(widget, ownerRead.get(5, TimeUnit.SECONDS));
                        } catch (java.util.concurrent.ExecutionException failure) {
                            if (failure.getCause() instanceof DashboardFailure typed) throw typed;
                            throw failure;
                        } finally {
                            if (!ownerRead.isDone()) ownerRead.cancel(true);
                        }
                    }
                    var scope = new ScopeSnapshot(actor.userId(), actor.accountId(), 0, 0, 0, List.of(), List.of(), false, asOf);
                    prepared.add(new Prepared(widget, null, scope, null, null));
                    continue;
                }
                Config config = decoder.config(widget, manifest.get());
                ScopeSnapshot scope = scopes.resolve(authorization, actor, defaults.scope(), runtime.scope(), decoder.scope(widget.binding()), scopeMemo, PositionWidgets.contains(widget.type()));
                scope=scopes.capabilities(authorization,actor,scope,widget.type());
                if (scope.deniedBindingCount() > 0) throw new DashboardFailure("NO_ACCESS", "Resource binding is no longer accessible");
                if (decoder.scope(widget.binding()).mode() == ScopeMode.UNBOUND)
                    throw new DashboardFailure("UNBOUND_RESOURCE", "No equipment is bound; unavailable measurements are not inferred");
                if (manifest.get().maxDetailDevices() > 0 && scope.evaluatedCount() > manifest.get().maxDetailDevices())
                    throw new DashboardFailure("LIMIT_EXCEEDED", "Detail scope exceeds the widget device limit");
                if (PositionWidgets.contains(widget.type()) && scope.devices().stream().anyMatch(device -> !"VEHICLE".equals(device.type())))
                    throw new DashboardFailure("VALIDATION_ERROR", "Vehicle map requires a VEHICLE device");
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
                if (PositionWidgets.contains(widget.type()) && java.time.Duration.between(frame.from(), frame.to()).compareTo(java.time.Duration.ofHours(24)) > 0)
                    throw new DashboardFailure("LIMIT_EXCEEDED", "Vehicle history supports at most 24 hours; narrow the period");
                var allowed = scope.devices().stream().map(DashboardEvidenceReads.Device::id).collect(java.util.stream.Collectors.toSet());
                if(scope.workspaceId()!=null) {
                    config=new Config(config.metric(),config.unit(),config.reducer(),config.time(),config.compare(),config.rows(),config.offset(),config.freshnessPolicy(),config.cadenceSeconds(),config.incidentMode(),config.series().stream().filter(series->allowed.contains(series.deviceId())).toList(),config.columns(),config.independentMetersConfirmed(),config.sourceId(),config.vehicleSources());
                    if("time-series".equals(widget.type()) && config.series().isEmpty()) throw new DashboardFailure("NO_ACCESS","Current series capabilities are unavailable");
                }
                if (config.series().stream().anyMatch(series -> !allowed.contains(series.deviceId())))
                    throw new DashboardFailure("NO_ACCESS", "Series binding is outside the resolved scope");
                if (validateOnly && List.of("latest-value", "metric-summary", "time-series").contains(widget.type())) {
                    if (capabilities == null) capabilities = scheduler.read("telemetry",
                            new ReadRequest(List.of(), actor.accountId(), frame, Set.of(), List.of(), 1, 0, "ACTIVE", List.of()),
                            authorization, actor, new HashMap<>()).get(5, TimeUnit.SECONDS);
                    Set<String> requestedMetrics = new java.util.HashSet<>();
                    if (config.metric() != null) requestedMetrics.add(config.metric());
                    config.series().forEach(series -> requestedMetrics.add(series.metric()));
                    Set<String> registeredKeys = new java.util.HashSet<>();
                    capabilities.path("registeredMetrics").forEach(metric -> registeredKeys.add(metric.path("key").asText()));
                    JsonNode selectedCapabilities = capabilities;
                    if (!registeredKeys.containsAll(requestedMetrics)) {
                        selectedCapabilities = scheduler.read("telemetry",
                                new ReadRequest(scope.devices().stream().map(DashboardEvidenceReads.Device::id).sorted().toList(),
                                        scope.accountId(), frame, requestedMetrics, List.of(), 1, 0, "ACTIVE", List.of(),
                                        false, scope.workspaceId(), Set.of()),
                                authorization, actor, new HashMap<>()).get(5, TimeUnit.SECONDS);
                    }
                    projection.registered(config, selectedCapabilities);
                }
                if (validateOnly && List.of("energy-period","peak-and-ranking").contains(widget.type())) {
                    var read=new ReadRequest(scope.devices().stream().map(DashboardEvidenceReads.Device::id).sorted().toList(),scope.accountId(),frame,
                            Set.of(),List.of(),config.rows(),config.offset(),"ACTIVE",List.of(),config.independentMetersConfirmed(),scope.workspaceId());
                    JsonNode validated=scheduler.read("energy-validation",read,authorization,actor,new HashMap<>()).get(5,TimeUnit.SECONDS);
                    if (validated.path("deniedBindingCount").asLong()>0) throw new DashboardFailure("NO_ACCESS","Meter resource access changed");
                    if (!validated.path("validationOnly").asBoolean() || validated.path("evaluatedCount").asLong(-1)!=scope.devices().size())
                        throw new DashboardFailure("INVALID_READ_RESPONSE","Invalid meter validation response");
                }
                if (validateOnly && PositionWidgets.contains(widget.type()) && !scope.devices().isEmpty()) {
                    var read = new ReadRequest(scope.devices().stream().map(DashboardEvidenceReads.Device::id).toList(), scope.accountId(), frame,
                            Set.of(), List.of(), 1, 0, "ACTIVE", List.of(), false, scope.workspaceId(), Set.of(), config.sourceId(),config.vehicleSources());
                    scheduler.read("positions", read, authorization, actor, new HashMap<>()).get(5, TimeUnit.SECONDS);
                }
                prepared.add(new Prepared(widget, config, scope, frame, null));
            } catch (DashboardFailure failure) {
                if ("LIMIT_EXCEEDED".equals(failure.code()) && !PositionWidgets.contains(widget.type())) throw failure;
                prepared.add(new Prepared(widget, null, null, null, failure));
            }
            catch (Exception failure) { prepared.add(new Prepared(widget, null, null, null,
                    new DashboardFailure("DEPENDENCY_UNAVAILABLE", "Capability owner is unavailable"))); }
        }
        // Authorization and source metadata were renewed above, before any cache lookup.
        List<Object> fingerprint = new ArrayList<>();
        for (Prepared panel : prepared) fingerprint.add(panel.scope() == null ? panel.failure().code()
                : List.of(panel.widget().id(), panel.scope().devices(), panel.scope().locations(),String.valueOf(panel.scope().authorizationFingerprint())));
        if (validateOnly) {
            String hash = QueryHash.of(mapper, List.of(actor, id, revision, fingerprint, asOf));
            return new Batch(id, revision, request.draftEtag(), hash, asOf, Instant.now(), false, prepared.stream()
                    .map(panel -> panel.failure() != null ? failed(panel, hash, panel.failure())
                            : new Panel(panel.widget().id(), "valid", null, hash, panel.frame(),
                                    Math.toIntExact(panel.scope().requestedCount()), Math.toIntExact(panel.scope().evaluatedCount()),
                                    Instant.now(), null, mapper.createObjectNode().put("validationOnly", true).put("scopeHash", scopeHash(panel)), null, null)).toList());
        }
        Map<QueryReadScheduler.Key, JsonNode> businessVersions = new HashMap<>();
        boolean businessValid = true;
        for (Prepared panel : prepared) if (panel.failure()==null && List.of("estimated-cost","uptime-or-cycle").contains(panel.widget().type())) {
            String owner = "estimated-cost".equals(panel.widget().type()) ? "cost" : "operations";
            var read = readRequest(actor,panel);
            var versionKey = new QueryReadScheduler.Key(owner,read);
            try {
                JsonNode version = businessVersions.computeIfAbsent(versionKey, ignored -> scheduler.businessVersion(authorization,actor,read,owner));
                if (version==null) businessValid=false;
                else fingerprint.add(List.of(panel.widget().id(), version));
            } catch (DashboardFailure failure) { businessValid=false; }
        }
        String key = QueryHash.of(mapper, List.of(actor, id, revision, request.draftEtag() == null ? "published" : request.draftEtag(),
                definition, runtime, fingerprint, request.widgetIds()==null ? List.of() : request.widgetIds()));
        boolean safe = businessValid && prepared.stream().noneMatch(panel -> PositionWidgets.contains(panel.widget().type())
                || WidgetManifests.configuredEvidence(panel.widget().type())) && prepared.stream().noneMatch(panel -> panel.failure() != null || panel.scope().workspaceId()!=null);
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> entry.getValue().until().isBefore(asOf));
            if (safe && !request.forceRefresh()) {
                Cached hit = cache.get(key);
                if (hit != null) {
                    Batch batch = hit.batch();
                    var result = new Batch(id, batch.revision(), batch.draftEtag(), batch.contextHash(), batch.asOf(), Instant.now(), true, batch.panels(), batch.scopes());
                    if (progress!=null) progress.accept(result);
                    return result;
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
            if (panel.failure() == null && WidgetManifests.configuredEvidence(panel.widget().type())) {
                try { owners.put("configured", scheduler.configuredEvidence(authorization, actor, panel.widget())); }
                catch (java.util.concurrent.RejectedExecutionException rejected) {
                    owners.put("configured", CompletableFuture.failedFuture(new DashboardFailure("DEPENDENCY_UNAVAILABLE", "Read concurrency budget is busy")));
                }
            } else if (panel.failure() == null) {
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
        var remaining = new java.util.LinkedHashSet<Integer>();
        for (int i=0;i<prepared.size();i++) remaining.add(i);
        try {
        while (!remaining.isEmpty()) {
            Integer ready = remaining.stream().filter(i -> prepared.get(i).failure()!=null || pending.get(i).values().stream().allMatch(CompletableFuture::isDone)).findFirst().orElse(null);
            if (ready==null && System.nanoTime()<deadline) {
                var completions = remaining.stream().map(i -> CompletableFuture.allOf(pending.get(i).values().toArray(CompletableFuture[]::new)).handle((value,error)->null)).toArray(CompletableFuture[]::new);
                try { CompletableFuture.anyOf(completions).get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
                catch (Exception ignored) { }
                ready = remaining.stream().filter(i -> pending.get(i).values().stream().allMatch(CompletableFuture::isDone)).findFirst().orElse(null);
            }
            int index = ready==null ? remaining.iterator().next() : ready;
            remaining.remove(index);
            Prepared panel = prepared.get(index);
            if (panel.failure() != null) { panels.add(failed(panel, contextHash, panel.failure())); }
            else {
            try {
                Map<String, JsonNode> evidence = new LinkedHashMap<>();
                for (var source : pending.get(index).entrySet()) {
                    long left = deadline - System.nanoTime();
                    JsonNode result = source.getValue().isDone() ? source.getValue().join()
                            : source.getValue().get(Math.max(1, left), TimeUnit.NANOSECONDS);
                    evidence.put(source.getKey(), result);
                }
                boolean configured = WidgetManifests.configuredEvidence(panel.widget().type());
                ObjectNode data = configured ? projectConfigured(panel.widget(), evidence.get("configured"))
                        : projection.project(panel.widget(), panel.config(), panel.frame(), panel.scope(), evidence);
                ObjectNode provenance = mapper.createObjectNode().put("consistency", "OWNER_READ_SNAPSHOTS_SHARED_QUERY_ASOF")
                        .put("readOnly", true).put("cachedSnapshotMaxAgeSeconds", 30).put("scopeHash", scopeHash(panel));
                evidence.forEach((owner, value) -> {
                    ObjectNode metadata = mapper.createObjectNode();
                    for (String field : List.of("queryAt", "asOf", "intervalBasis", "weighting", "maintenancePolicy", "gapPolicy", "source", "timeBasis", "listTimeBasis", "confidenceMeaning", "riskMeaning"))
                        if (value.has(field)) metadata.set(field, value.get(field));
                    provenance.set(owner, metadata);
                });
                if (configured) provenance.put("consistency", switch (panel.widget().type()) {
                    case NativeDashboardRows.TYPE -> "NATIVE_TABLE_LIVE";
                    default -> "STORED_RULE_MEASURE";
                }).put("cachedSnapshotMaxAgeSeconds", 0);
                String status = !configured && noData(panel, data, evidence) ? "no_data" : "ready";
                panels.add(new Panel(panel.widget().id(), status, data, contextHash, panel.frame(),
                        Math.toIntExact(panel.scope().requestedCount()), Math.toIntExact(panel.scope().evaluatedCount()),
                        Instant.now(), configured ? null : measuredAt(panel, data, evidence), provenance, null, null));
            } catch (Exception exception) {
                Throwable cause = exception;
                while (cause.getCause() != null && !(cause instanceof DashboardFailure)) cause = cause.getCause();
                DashboardFailure failure = cause instanceof DashboardFailure typed ? typed
                        : new DashboardFailure(exception instanceof java.util.concurrent.TimeoutException ? "READ_TIMEOUT" : "DEPENDENCY_UNAVAILABLE", "Owner evidence is unavailable");
                panels.add(failed(panel, contextHash, failure));
            }
            }
            if (progress!=null) progress.accept(compact.compact(actor,request,new Batch(id,revision,request.draftEtag(),contextHash,asOf,Instant.now(),false,List.of(panels.getLast()))));
        }
        } finally {
            reads.values().forEach(future -> { if (!future.isDone()) future.cancel(true); });
            pending.forEach(owners -> owners.values().forEach(future -> { if (!future.isDone()) future.cancel(true); }));
        }
        var order = new HashMap<String,Integer>();
        for (int i=0;i<prepared.size();i++) order.put(prepared.get(i).widget().id(),i);
        panels.sort(java.util.Comparator.comparingInt(panel -> order.get(panel.widgetId())));
        Batch batch = new Batch(id, revision, request.draftEtag(), contextHash, asOf, Instant.now(), false, List.copyOf(panels));
        reads.values().forEach(future -> {
            if (!future.isDone()) future.cancel(false);
        });
        if (panels.stream().anyMatch(panel -> "no_access".equals(panel.status()))) synchronized (cache) {
            cache.entrySet().removeIf(entry -> entry.getValue().actor().equals(actor));
        }
        Batch wire = unsaved ? batch : compact.compact(actor,request,batch);
        int bytes;
        try { bytes=mapper.writeValueAsBytes(wire).length; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        if (!unsaved && safe && panels.stream().allMatch(panel -> "ready".equals(panel.status()) || "no_data".equals(panel.status()))
                && bytes <= 8_000_000) synchronized (cache) {
            cache.remove(key);
            while (!cache.isEmpty() && (cache.size() >= 64 || cache.values().stream().mapToLong(Cached::bytes).sum()+bytes>16_000_000))
                cache.remove(cache.keySet().iterator().next());
            cache.put(key, new Cached(actor, asOf.plusSeconds(30), wire, bytes));
        }
        return wire;
    }
    public Panel snapshotDetails(String authorization,DashboardActor actor,UUID id,String hash,String widgetId) {
        return compact.details(authorization,actor,id,hash,widgetId,this);
    }

    private ReadRequest readRequest(DashboardActor actor, Prepared panel) {
        Config config = panel.config();
        List<UUID> ids = panel.scope().devices().stream().map(DashboardEvidenceReads.Device::id).sorted().toList();
        Set<String> metrics = new java.util.TreeSet<>();
        if (config.metric() != null) metrics.add(config.metric());
        config.series().forEach(series -> metrics.add(series.metric()));
        return new ReadRequest(ids, panel.scope().accountId(), panel.frame(), metrics,
                manifests.find(panel.widget().type(), panel.widget().typeVersion()).orElseThrow().maxDetailDevices() > 0 ? ids : List.of(),
                config.rows(), config.offset(), config.incidentMode() == null ? "ACTIVE" : config.incidentMode().name(), config.series(),config.independentMetersConfirmed(),panel.scope().workspaceId(),
                switch (panel.widget().type()) {
                    case "fleet-count", "latest-value", "location-matrix" -> Set.of("latest");
                    case "quality-and-freshness" -> Set.of("latest", "quality");
                    case "time-series" -> Set.of("series");
                    case "metric-summary" -> Set.of("summaries");
                    default -> Set.of();
                },config.sourceId(),config.vehicleSources());
    }
    private ObjectNode projectConfigured(Widget widget, JsonNode evidence) {
        var data = mapper.createObjectNode();
        if (NativeDashboardRows.TYPE.equals(widget.type())) NativeDashboardRows.project(data, evidence, widget.query());
        else DashboardRuleMeasure.parse(widget.query()).project(data, evidence);
        return data;
    }

    private String scopeHash(Prepared panel) {
        return QueryHash.of(mapper, List.of(panel.scope().devices(), panel.scope().locations(), String.valueOf(panel.scope().authorizationFingerprint())));
    }
    private Panel failed(Prepared panel, String hash, DashboardFailure failure) {
        String status = switch (failure.code()) { case "UNBOUND_RESOURCE" -> "no_data"; case "NO_ACCESS" -> "no_access"; case "UNSUPPORTED_WIDGET" -> "unsupported";
            case "VALIDATION_ERROR", "LIMIT_EXCEEDED" -> "invalid"; default -> "unavailable"; };
        return new Panel(panel.widget().id(), status, null, hash, panel.frame(), 0, 0, Instant.now(), null,
                mapper.createObjectNode().put("readOnly", true), failure.code(), failure.getMessage());
    }
    private Instant measuredAt(Prepared panel, JsonNode data, Map<String, JsonNode> evidence) {
        if (PositionWidgets.contains(panel.widget().type())) {
            if (data.path("positions").has("fleet")) {
                return java.util.stream.StreamSupport.stream(data.path("positions").path("fleet").spliterator(),false)
                        .map(item -> parseTime(item.path("position").path("recordedAt")))
                        .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
            }
            return parseTime(data.path("positions").path("latest").path("position").path("recordedAt"));
        }
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
            case "vehicle-timeline" -> data.path("positions").path("history").path("points").isEmpty();
            case "vehicle-map", "vehicle-value" -> data.path("positions").has("fleet") ? java.util.stream.StreamSupport.stream(data.path("positions").path("fleet").spliterator(),false).noneMatch(item -> item.hasNonNull("position")) : !data.path("positions").path("latest").hasNonNull("position");
            case "latest-value" -> data.path("value").isNull();
            case "metric-summary" -> data.path("summary").isNull();
            case "energy-period" -> !data.path("energy").hasNonNull("energyKWh");
            case "peak-and-ranking" -> data.path("energy").path("energyRanking").isEmpty() && data.path("energy").path("peakRanking").isEmpty();
            case "time-series" -> !data.path("series").isArray() || data.path("series").isEmpty()
                    || java.util.stream.StreamSupport.stream(data.path("series").spliterator(), false).allMatch(series -> series.path("buckets").isEmpty());
            case "risk-list", "prediction-evidence" -> data.path("total").asInt() == 0;
            case "incident-list" -> evidence.get("incidents").path("total").asLong(-1) == 0;
            case "maintenance-agenda" -> evidence.get("maintenance").path("total").asLong(-1) == 0;
            default -> false;
        };
    }
}
