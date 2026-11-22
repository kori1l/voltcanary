package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardDefinition.Widget;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardEvidenceReads;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ReadRequest;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ScopeSnapshot;
import com.voltcanary.dashboard.query.QueryTypes.Config;
import com.voltcanary.dashboard.query.QueryTypes.Frame;
import com.voltcanary.dashboard.query.QueryTypes.ScopeMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Validates widgets and renews source/account authority before any cache lookup. */
final class WidgetQueryPreparation {

    private final WidgetManifests manifests;
    private final WidgetQueryDecoder decoder;
    private final WidgetScopeResolver scopes;
    private final QueryTimeResolver time;
    private final QueryReadScheduler scheduler;
    private final WidgetEvidenceProjection projection;
    private final ObjectMapper mapper;

    WidgetQueryPreparation(
        WidgetManifests manifests,
        WidgetQueryDecoder decoder,
        WidgetScopeResolver scopes,
        QueryTimeResolver time,
        QueryReadScheduler scheduler,
        WidgetEvidenceProjection projection,
        ObjectMapper mapper
    ) {
        this.manifests = manifests;
        this.decoder = decoder;
        this.scopes = scopes;
        this.time = time;
        this.scheduler = scheduler;
        this.projection = projection;
        this.mapper = mapper;
    }

    record Prepared(
        Widget widget,
        Config config,
        ScopeSnapshot scope,
        Frame frame,
        DashboardFailure failure
    ) {}

    QueryTypes.RuntimeContext runtime(DashboardWidgetQueries.Request request) {
        return decoder.runtime(request.runtimeContext());
    }

    List<Prepared> prepare(
        String authorization,
        DashboardActor actor,
        UUID id,
        DashboardWidgetQueries.Request request,
        DashboardDefinition definition,
        Instant asOf,
        boolean validateOnly
    ) {
        var runtime = decoder.runtime(request.runtimeContext());
        var defaults = decoder.runtime(definition.defaultContext());
        List<Prepared> prepared = new ArrayList<>();
        Map<QueryTypes.Scope, ScopeSnapshot> scopeMemo = new HashMap<>();
        JsonNode capabilities = null;
        for (Widget widget : definition.widgets()) {
            if (request.widgetIds() != null && !request.widgetIds().contains(widget.id())) continue;
            var manifest = manifests.find(widget.type(), widget.typeVersion());
            if (manifest.isEmpty() || !manifest.get().supported()) {
                prepared.add(
                    new Prepared(
                        widget,
                        null,
                        null,
                        null,
                        new DashboardFailure(
                            "UNSUPPORTED_WIDGET",
                            "Widget version or dependency is unavailable"
                        )
                    )
                );
                continue;
            }
            try {
                if (WidgetManifests.configuredEvidence(widget.type())) {
                    if (
                        NativeDashboardRows.TYPE.equals(widget.type())
                    ) NativeDashboardRows.validate(widget.query());
                    else DashboardRuleMeasure.parse(widget.query());
                    if (
                        defaults.scope().workspaceId() != null ||
                        runtime.scope().workspaceId() != null ||
                        decoder.scope(widget.binding()).workspaceId() != null
                    ) {
                        throw new DashboardFailure(
                            "NO_ACCESS",
                            "Configured evidence requires a private dashboard"
                        );
                    }
                    if (validateOnly) {
                        var ownerRead = scheduler.configuredEvidence(authorization, actor, widget);
                        try {
                            ConfiguredWidgetProjection.project(
                                mapper,
                                widget,
                                ownerRead.get(5, TimeUnit.SECONDS)
                            );
                        } catch (java.util.concurrent.ExecutionException failure) {
                            if (failure.getCause() instanceof DashboardFailure typed) throw typed;
                            throw failure;
                        } finally {
                            if (!ownerRead.isDone()) ownerRead.cancel(true);
                        }
                    }
                    var scope = new ScopeSnapshot(
                        actor.userId(),
                        actor.accountId(),
                        0,
                        0,
                        0,
                        List.of(),
                        List.of(),
                        false,
                        asOf
                    );
                    prepared.add(new Prepared(widget, null, scope, null, null));
                    continue;
                }
                Config config = decoder.config(widget, manifest.get());
                ScopeSnapshot scope = scopes.resolve(
                    authorization,
                    actor,
                    defaults.scope(),
                    runtime.scope(),
                    decoder.scope(widget.binding()),
                    scopeMemo,
                    PositionWidgets.contains(widget.type())
                );
                scope = scopes.capabilities(authorization, actor, scope, widget.type());
                if (scope.deniedBindingCount() > 0) throw new DashboardFailure(
                    "NO_ACCESS",
                    "Resource binding is no longer accessible"
                );
                if (
                    decoder.scope(widget.binding()).mode() == ScopeMode.UNBOUND
                ) throw new DashboardFailure(
                    "UNBOUND_RESOURCE",
                    "No equipment is bound; unavailable measurements are not inferred"
                );
                if (
                    manifest.get().maxDetailDevices() > 0 &&
                    scope.evaluatedCount() > manifest.get().maxDetailDevices()
                ) throw new DashboardFailure(
                    "LIMIT_EXCEEDED",
                    "Detail scope exceeds the widget device limit"
                );
                if (
                    PositionWidgets.contains(widget.type()) &&
                    scope
                        .devices()
                        .stream()
                        .anyMatch(device -> !"VEHICLE".equals(device.type()))
                ) throw new DashboardFailure(
                    "VALIDATION_ERROR",
                    "Vehicle map requires a VEHICLE device"
                );
                if (
                    "latest-value".equals(widget.type()) &&
                    (scope.evaluatedCount() != 1 || config.metric() == null)
                ) throw new DashboardFailure(
                    "VALIDATION_ERROR",
                    "Latest value requires one device and a metric"
                );
                if (
                    "metric-summary".equals(widget.type()) && config.metric() == null
                ) throw new DashboardFailure("VALIDATION_ERROR", "Summary requires a metric");
                if (
                    "time-series".equals(widget.type()) && config.series().isEmpty()
                ) throw new DashboardFailure(
                    "VALIDATION_ERROR",
                    "Select at least one typed series"
                );
                if (
                    "context-note".equals(widget.type()) &&
                    widget.payload() != null &&
                    widget.payload().path("text").asText().length() > 1000
                ) throw new DashboardFailure(
                    "LIMIT_EXCEEDED",
                    "Notes are limited to 1000 characters"
                );
                Frame frame = time.resolve(
                    request.runtimeContext() == null || !request.runtimeContext().hasNonNull("time")
                        ? defaults.time()
                        : runtime.time(),
                    config.time(),
                    config.compare(),
                    asOf
                );
                if (
                    !"maintenance-agenda".equals(widget.type()) && frame.to().isAfter(asOf)
                ) throw new DashboardFailure(
                    "VALIDATION_ERROR",
                    "Future intervals are available only for maintenance"
                );
                if (
                    PositionWidgets.contains(widget.type()) &&
                    java.time.Duration.between(frame.from(), frame.to()).compareTo(
                        java.time.Duration.ofHours(24)
                    ) > 0
                ) throw new DashboardFailure(
                    "LIMIT_EXCEEDED",
                    "Vehicle history supports at most 24 hours; narrow the period"
                );
                var allowed = scope
                    .devices()
                    .stream()
                    .map(DashboardEvidenceReads.Device::id)
                    .collect(java.util.stream.Collectors.toSet());
                if (scope.workspaceId() != null) {
                    config = new Config(
                        config.metric(),
                        config.unit(),
                        config.reducer(),
                        config.time(),
                        config.compare(),
                        config.rows(),
                        config.offset(),
                        config.freshnessPolicy(),
                        config.cadenceSeconds(),
                        config.incidentMode(),
                        config
                            .series()
                            .stream()
                            .filter(series -> allowed.contains(series.deviceId()))
                            .toList(),
                        config.columns(),
                        config.independentMetersConfirmed(),
                        config.sourceId(),
                        config.vehicleSources()
                    );
                    if (
                        "time-series".equals(widget.type()) && config.series().isEmpty()
                    ) throw new DashboardFailure(
                        "NO_ACCESS",
                        "Current series capabilities are unavailable"
                    );
                }
                if (
                    config
                        .series()
                        .stream()
                        .anyMatch(series -> !allowed.contains(series.deviceId()))
                ) throw new DashboardFailure(
                    "NO_ACCESS",
                    "Series binding is outside the resolved scope"
                );
                if (
                    validateOnly &&
                    List.of("latest-value", "metric-summary", "time-series").contains(widget.type())
                ) {
                    if (capabilities == null) capabilities = scheduler
                        .read(
                            "telemetry",
                            new ReadRequest(
                                List.of(),
                                actor.accountId(),
                                frame,
                                Set.of(),
                                List.of(),
                                1,
                                0,
                                "ACTIVE",
                                List.of()
                            ),
                            authorization,
                            actor,
                            new HashMap<>()
                        )
                        .get(5, TimeUnit.SECONDS);
                    Set<String> requestedMetrics = new java.util.HashSet<>();
                    if (config.metric() != null) requestedMetrics.add(config.metric());
                    config.series().forEach(series -> requestedMetrics.add(series.metric()));
                    Set<String> registeredKeys = new java.util.HashSet<>();
                    capabilities
                        .path("registeredMetrics")
                        .forEach(metric -> registeredKeys.add(metric.path("key").asText()));
                    JsonNode selectedCapabilities = capabilities;
                    if (!registeredKeys.containsAll(requestedMetrics)) {
                        selectedCapabilities = scheduler
                            .read(
                                "telemetry",
                                new ReadRequest(
                                    scope
                                        .devices()
                                        .stream()
                                        .map(DashboardEvidenceReads.Device::id)
                                        .sorted()
                                        .toList(),
                                    scope.accountId(),
                                    frame,
                                    requestedMetrics,
                                    List.of(),
                                    1,
                                    0,
                                    "ACTIVE",
                                    List.of(),
                                    false,
                                    scope.workspaceId(),
                                    Set.of()
                                ),
                                authorization,
                                actor,
                                new HashMap<>()
                            )
                            .get(5, TimeUnit.SECONDS);
                    }
                    projection.registered(config, selectedCapabilities);
                }
                if (
                    validateOnly &&
                    List.of("energy-period", "peak-and-ranking").contains(widget.type())
                ) {
                    var read = new ReadRequest(
                        scope
                            .devices()
                            .stream()
                            .map(DashboardEvidenceReads.Device::id)
                            .sorted()
                            .toList(),
                        scope.accountId(),
                        frame,
                        Set.of(),
                        List.of(),
                        config.rows(),
                        config.offset(),
                        "ACTIVE",
                        List.of(),
                        config.independentMetersConfirmed(),
                        scope.workspaceId()
                    );
                    JsonNode validated = scheduler
                        .read("energy-validation", read, authorization, actor, new HashMap<>())
                        .get(5, TimeUnit.SECONDS);
                    if (
                        validated.path("deniedBindingCount").asLong() > 0
                    ) throw new DashboardFailure("NO_ACCESS", "Meter resource access changed");
                    if (
                        !validated.path("validationOnly").asBoolean() ||
                        validated.path("evaluatedCount").asLong(-1) != scope.devices().size()
                    ) throw new DashboardFailure(
                        "INVALID_READ_RESPONSE",
                        "Invalid meter validation response"
                    );
                }
                if (
                    validateOnly &&
                    PositionWidgets.contains(widget.type()) &&
                    !scope.devices().isEmpty()
                ) {
                    var read = new ReadRequest(
                        scope.devices().stream().map(DashboardEvidenceReads.Device::id).toList(),
                        scope.accountId(),
                        frame,
                        Set.of(),
                        List.of(),
                        1,
                        0,
                        "ACTIVE",
                        List.of(),
                        false,
                        scope.workspaceId(),
                        Set.of(),
                        config.sourceId(),
                        config.vehicleSources()
                    );
                    scheduler
                        .read("positions", read, authorization, actor, new HashMap<>())
                        .get(5, TimeUnit.SECONDS);
                }
                prepared.add(new Prepared(widget, config, scope, frame, null));
            } catch (DashboardFailure failure) {
                if (
                    "LIMIT_EXCEEDED".equals(failure.code()) &&
                    !PositionWidgets.contains(widget.type())
                ) throw failure;
                prepared.add(new Prepared(widget, null, null, null, failure));
            } catch (Exception failure) {
                QueryFailureDiagnostics.unexpected(id, widget.id(), "prepare", failure);
                prepared.add(
                    new Prepared(
                        widget,
                        null,
                        null,
                        null,
                        new DashboardFailure(
                            "DEPENDENCY_UNAVAILABLE",
                            "Capability owner is unavailable"
                        )
                    )
                );
            }
        }
        return List.copyOf(prepared);
    }
}
