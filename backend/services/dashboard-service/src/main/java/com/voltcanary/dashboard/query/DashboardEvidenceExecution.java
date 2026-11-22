package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardEvidenceReads;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ReadRequest;
import com.voltcanary.dashboard.query.QueryTypes.Batch;
import com.voltcanary.dashboard.query.QueryTypes.Config;
import com.voltcanary.dashboard.query.QueryTypes.Panel;
import com.voltcanary.dashboard.query.WidgetQueryPreparation.Prepared;
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

/** Bounded owner reads, completion handling and result projection for already authorized widgets. */
final class DashboardEvidenceExecution {

    private final WidgetManifests manifests;
    private final QueryReadScheduler scheduler;
    private final WidgetEvidenceProjection projection;
    private final ObjectMapper mapper;
    private final CompactDashboardEvidence compact;

    DashboardEvidenceExecution(
        WidgetManifests manifests,
        QueryReadScheduler scheduler,
        WidgetEvidenceProjection projection,
        ObjectMapper mapper,
        CompactDashboardEvidence compact
    ) {
        this.manifests = manifests;
        this.scheduler = scheduler;
        this.projection = projection;
        this.mapper = mapper;
        this.compact = compact;
    }

    record CacheContext(List<Object> fingerprint, boolean safe) {}

    private List<Object> scopeFingerprint(List<Prepared> prepared) {
        List<Object> fingerprint = new ArrayList<>();
        for (Prepared panel : prepared)
            fingerprint.add(
                panel.scope() == null
                    ? panel.failure().code()
                    : List.of(
                          panel.widget().id(),
                          panel.scope().devices(),
                          panel.scope().locations(),
                          String.valueOf(panel.scope().authorizationFingerprint())
                      )
            );
        return fingerprint;
    }

    Batch validation(
        DashboardActor actor,
        UUID id,
        int revision,
        DashboardWidgetQueries.Request request,
        Instant asOf,
        List<Prepared> prepared
    ) {
        var fingerprint = scopeFingerprint(prepared);
        String hash = QueryHash.of(mapper, List.of(actor, id, revision, fingerprint, asOf));
        return new Batch(
            id,
            revision,
            request.draftEtag(),
            hash,
            asOf,
            Instant.now(),
            false,
            prepared
                .stream()
                .map(panel ->
                    panel.failure() != null
                        ? failed(panel, hash, panel.failure())
                        : new Panel(
                              panel.widget().id(),
                              "valid",
                              null,
                              hash,
                              panel.frame(),
                              Math.toIntExact(panel.scope().requestedCount()),
                              Math.toIntExact(panel.scope().evaluatedCount()),
                              Instant.now(),
                              null,
                              mapper
                                  .createObjectNode()
                                  .put("validationOnly", true)
                                  .put("scopeHash", scopeHash(panel)),
                              null,
                              null
                          )
                )
                .toList()
        );
    }

    CacheContext cacheContext(String authorization, DashboardActor actor, List<Prepared> prepared) {
        var fingerprint = scopeFingerprint(prepared);
        Map<QueryReadScheduler.Key, JsonNode> businessVersions = new HashMap<>();
        boolean businessValid = true;
        for (Prepared panel : prepared)
            if (
                panel.failure() == null &&
                List.of("estimated-cost", "uptime-or-cycle").contains(panel.widget().type())
            ) {
                String owner = "estimated-cost".equals(panel.widget().type())
                    ? "cost"
                    : "operations";
                var read = readRequest(actor, panel);
                var versionKey = new QueryReadScheduler.Key(owner, read);
                try {
                    JsonNode version = businessVersions.computeIfAbsent(versionKey, ignored ->
                        scheduler.businessVersion(authorization, actor, read, owner)
                    );
                    if (version == null) businessValid = false;
                    else fingerprint.add(List.of(panel.widget().id(), version));
                } catch (DashboardFailure failure) {
                    businessValid = false;
                }
            }
        boolean safe =
            businessValid &&
            prepared
                .stream()
                .noneMatch(
                    panel ->
                        PositionWidgets.contains(panel.widget().type()) ||
                        WidgetManifests.configuredEvidence(panel.widget().type())
                ) &&
            prepared
                .stream()
                .noneMatch(panel -> panel.failure() != null || panel.scope().workspaceId() != null);
        return new CacheContext(List.copyOf(fingerprint), safe);
    }

    Batch query(
        String authorization,
        DashboardActor actor,
        UUID id,
        int revision,
        DashboardWidgetQueries.Request request,
        Instant asOf,
        String contextHash,
        List<Prepared> prepared,
        java.util.function.Consumer<Batch> progress
    ) {
        Map<QueryReadScheduler.Key, CompletableFuture<JsonNode>> reads = new HashMap<>();
        List<Map<String, CompletableFuture<JsonNode>>> pending = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (Prepared panel : prepared) {
            Map<String, CompletableFuture<JsonNode>> owners = new LinkedHashMap<>();
            if (
                panel.failure() == null && WidgetManifests.configuredEvidence(panel.widget().type())
            ) {
                try {
                    owners.put(
                        "configured",
                        scheduler.configuredEvidence(authorization, actor, panel.widget())
                    );
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    owners.put(
                        "configured",
                        CompletableFuture.failedFuture(
                            new DashboardFailure(
                                "DEPENDENCY_UNAVAILABLE",
                                "Read concurrency budget is busy"
                            )
                        )
                    );
                }
            } else if (panel.failure() == null) {
                ReadRequest read = readRequest(actor, panel);
                for (String owner : projection.owners(panel.widget().type())) {
                    try {
                        owners.put(owner, scheduler.read(owner, read, authorization, actor, reads));
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        owners.put(
                            owner,
                            CompletableFuture.failedFuture(
                                new DashboardFailure(
                                    "DEPENDENCY_UNAVAILABLE",
                                    "Read concurrency budget is busy"
                                )
                            )
                        );
                    }
                }
            }
            pending.add(owners);
        }
        List<Panel> panels = new ArrayList<>();
        var remaining = new java.util.LinkedHashSet<Integer>();
        for (int i = 0; i < prepared.size(); i++) remaining.add(i);
        try {
            while (!remaining.isEmpty()) {
                Integer ready = remaining
                    .stream()
                    .filter(
                        i ->
                            prepared.get(i).failure() != null ||
                            pending.get(i).values().stream().allMatch(CompletableFuture::isDone)
                    )
                    .findFirst()
                    .orElse(null);
                if (ready == null && System.nanoTime() < deadline) {
                    var completions = remaining
                        .stream()
                        .map(i ->
                            CompletableFuture.allOf(
                                pending.get(i).values().toArray(CompletableFuture[]::new)
                            ).handle((value, error) -> null)
                        )
                        .toArray(CompletableFuture[]::new);
                    try {
                        CompletableFuture.anyOf(completions).get(
                            Math.max(1, deadline - System.nanoTime()),
                            TimeUnit.NANOSECONDS
                        );
                    } catch (Exception ignored) {}
                    ready = remaining
                        .stream()
                        .filter(i ->
                            pending.get(i).values().stream().allMatch(CompletableFuture::isDone)
                        )
                        .findFirst()
                        .orElse(null);
                }
                int index = ready == null ? remaining.iterator().next() : ready;
                remaining.remove(index);
                Prepared panel = prepared.get(index);
                if (panel.failure() != null) {
                    panels.add(failed(panel, contextHash, panel.failure()));
                } else {
                    try {
                        Map<String, JsonNode> evidence = new LinkedHashMap<>();
                        for (var source : pending.get(index).entrySet()) {
                            long left = deadline - System.nanoTime();
                            JsonNode result = source.getValue().isDone()
                                ? source.getValue().join()
                                : source.getValue().get(Math.max(1, left), TimeUnit.NANOSECONDS);
                            evidence.put(source.getKey(), result);
                        }
                        boolean configured = WidgetManifests.configuredEvidence(
                            panel.widget().type()
                        );
                        ObjectNode data = configured
                            ? ConfiguredWidgetProjection.project(
                                  mapper,
                                  panel.widget(),
                                  evidence.get("configured")
                              )
                            : projection.project(
                                  panel.widget(),
                                  panel.config(),
                                  panel.frame(),
                                  panel.scope(),
                                  evidence
                              );
                        ObjectNode provenance = mapper
                            .createObjectNode()
                            .put("consistency", "OWNER_READ_SNAPSHOTS_SHARED_QUERY_ASOF")
                            .put("readOnly", true)
                            .put("cachedSnapshotMaxAgeSeconds", 30)
                            .put("scopeHash", scopeHash(panel));
                        evidence.forEach((owner, value) -> {
                            ObjectNode metadata = mapper.createObjectNode();
                            for (String field : List.of(
                                "queryAt",
                                "asOf",
                                "intervalBasis",
                                "weighting",
                                "maintenancePolicy",
                                "gapPolicy",
                                "source",
                                "timeBasis",
                                "listTimeBasis",
                                "confidenceMeaning",
                                "riskMeaning"
                            ))
                                if (value.has(field)) metadata.set(field, value.get(field));
                            provenance.set(owner, metadata);
                        });
                        if (configured) provenance
                            .put(
                                "consistency",
                                switch (panel.widget().type()) {
                                    case NativeDashboardRows.TYPE -> "NATIVE_TABLE_LIVE";
                                    default -> "STORED_RULE_MEASURE";
                                }
                            )
                            .put("cachedSnapshotMaxAgeSeconds", 0);
                        String status =
                            !configured && noData(panel, data, evidence) ? "no_data" : "ready";
                        panels.add(
                            new Panel(
                                panel.widget().id(),
                                status,
                                data,
                                contextHash,
                                panel.frame(),
                                Math.toIntExact(panel.scope().requestedCount()),
                                Math.toIntExact(panel.scope().evaluatedCount()),
                                Instant.now(),
                                configured ? null : measuredAt(panel, data, evidence),
                                provenance,
                                null,
                                null
                            )
                        );
                    } catch (Exception exception) {
                        QueryFailureDiagnostics.unexpected(
                            id,
                            panel.widget().id(),
                            "project",
                            exception
                        );
                        Throwable cause = exception;
                        while (cause.getCause() != null && !(cause instanceof DashboardFailure))
                            cause = cause.getCause();
                        DashboardFailure failure =
                            cause instanceof DashboardFailure typed
                                ? typed
                                : new DashboardFailure(
                                      exception instanceof java.util.concurrent.TimeoutException
                                          ? "READ_TIMEOUT"
                                          : "DEPENDENCY_UNAVAILABLE",
                                      "Owner evidence is unavailable"
                                  );
                        panels.add(failed(panel, contextHash, failure));
                    }
                }
                if (progress != null) progress.accept(
                    compact.compact(
                        actor,
                        request,
                        new Batch(
                            id,
                            revision,
                            request.draftEtag(),
                            contextHash,
                            asOf,
                            Instant.now(),
                            false,
                            List.of(panels.getLast())
                        )
                    )
                );
            }
        } finally {
            reads.values().forEach(future -> {
                if (!future.isDone()) future.cancel(true);
            });
            pending.forEach(owners ->
                owners.values().forEach(future -> {
                    if (!future.isDone()) future.cancel(true);
                })
            );
        }
        var order = new HashMap<String, Integer>();
        for (int i = 0; i < prepared.size(); i++) order.put(prepared.get(i).widget().id(), i);
        panels.sort(java.util.Comparator.comparingInt(panel -> order.get(panel.widgetId())));
        Batch batch = new Batch(
            id,
            revision,
            request.draftEtag(),
            contextHash,
            asOf,
            Instant.now(),
            false,
            List.copyOf(panels)
        );
        reads.values().forEach(future -> {
            if (!future.isDone()) future.cancel(false);
        });
        return batch;
    }

    private ReadRequest readRequest(DashboardActor actor, Prepared panel) {
        Config config = panel.config();
        List<UUID> ids = panel
            .scope()
            .devices()
            .stream()
            .map(DashboardEvidenceReads.Device::id)
            .sorted()
            .toList();
        Set<String> metrics = new java.util.TreeSet<>();
        if (config.metric() != null) metrics.add(config.metric());
        config.series().forEach(series -> metrics.add(series.metric()));
        return new ReadRequest(
            ids,
            panel.scope().accountId(),
            panel.frame(),
            metrics,
            manifests
                .find(panel.widget().type(), panel.widget().typeVersion())
                .orElseThrow()
                .maxDetailDevices() > 0
                ? ids
                : List.of(),
            config.rows(),
            config.offset(),
            config.incidentMode() == null ? "ACTIVE" : config.incidentMode().name(),
            config.series(),
            config.independentMetersConfirmed(),
            panel.scope().workspaceId(),
            switch (panel.widget().type()) {
                case "fleet-count", "latest-value", "location-matrix" -> Set.of("latest");
                case "quality-and-freshness" -> Set.of("latest", "quality");
                case "time-series" -> Set.of("series");
                case "metric-summary" -> Set.of("summaries");
                default -> Set.of();
            },
            config.sourceId(),
            config.vehicleSources()
        );
    }

    private String scopeHash(Prepared panel) {
        return QueryHash.of(
            mapper,
            List.of(
                panel.scope().devices(),
                panel.scope().locations(),
                String.valueOf(panel.scope().authorizationFingerprint())
            )
        );
    }

    private Panel failed(Prepared panel, String hash, DashboardFailure failure) {
        String status = switch (failure.code()) {
            case "UNBOUND_RESOURCE" -> "no_data";
            case "NO_ACCESS" -> "no_access";
            case "UNSUPPORTED_WIDGET" -> "unsupported";
            case "VALIDATION_ERROR", "LIMIT_EXCEEDED" -> "invalid";
            default -> "unavailable";
        };
        return new Panel(
            panel.widget().id(),
            status,
            null,
            hash,
            panel.frame(),
            0,
            0,
            Instant.now(),
            null,
            mapper.createObjectNode().put("readOnly", true),
            failure.code(),
            failure.getMessage()
        );
    }

    private Instant measuredAt(Prepared panel, JsonNode data, Map<String, JsonNode> evidence) {
        if (PositionWidgets.contains(panel.widget().type())) {
            if (data.path("positions").has("fleet")) {
                return java.util.stream.StreamSupport.stream(
                    data.path("positions").path("fleet").spliterator(),
                    false
                )
                    .map(item -> parseTime(item.path("position").path("recordedAt")))
                    .filter(java.util.Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(null);
            }
            return parseTime(
                data.path("positions").path("latest").path("position").path("recordedAt")
            );
        }
        if ("metric-summary".equals(panel.widget().type())) return parseTime(
            data.path("summary").path("measuredAt")
        );
        if ("latest-value".equals(panel.widget().type())) return parseTime(
            data.path("value").path("measuredAt")
        );
        if ("time-series".equals(panel.widget().type())) {
            Instant latest = null;
            for (JsonNode series : data.path("series"))
                for (JsonNode bucket : series.path("buckets")) {
                    Instant at = parseTime(bucket.path("lastAt"));
                    if (at != null && (latest == null || at.isAfter(latest))) latest = at;
                }
            return latest;
        }
        Instant latest = null;
        for (JsonNode source : evidence.values())
            for (String key : List.of("latest", "summaries", "risks", "predictions"))
                for (JsonNode row : source.path(key))
                    if (row.hasNonNull("measuredAt")) {
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
            case "vehicle-timeline" -> data.path("positions")
                .path("history")
                .path("points")
                .isEmpty();
            case "vehicle-map", "vehicle-value" -> data.path("positions").has("fleet")
                ? java.util.stream.StreamSupport.stream(
                      data.path("positions").path("fleet").spliterator(),
                      false
                  ).noneMatch(item -> item.hasNonNull("position"))
                : !data.path("positions").path("latest").hasNonNull("position");
            case "latest-value" -> data.path("value").isNull();
            case "metric-summary" -> data.path("summary").isNull();
            case "energy-period" -> !data.path("energy").hasNonNull("energyKWh");
            case "peak-and-ranking" -> data.path("energy").path("energyRanking").isEmpty() &&
                data.path("energy").path("peakRanking").isEmpty();
            case "time-series" -> !data.path("series").isArray() ||
                data.path("series").isEmpty() ||
                java.util.stream.StreamSupport.stream(
                    data.path("series").spliterator(),
                    false
                ).allMatch(series -> series.path("buckets").isEmpty());
            case "risk-list", "prediction-evidence" -> data.path("total").asInt() == 0;
            case "incident-list" -> evidence.get("incidents").path("total").asLong(-1) == 0;
            case "maintenance-agenda" -> evidence.get("maintenance").path("total").asLong(-1) == 0;
            default -> false;
        };
    }
}
