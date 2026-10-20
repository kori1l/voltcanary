package com.voltcanary.dashboard.query;

import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardEvidenceReads;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ScopeSnapshot;
import com.voltcanary.dashboard.query.QueryTypes.Scope;
import com.voltcanary.dashboard.query.QueryTypes.ScopeMode;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WidgetScopeResolver {
    private final DashboardEvidenceReads reads;
    public WidgetScopeResolver(DashboardEvidenceReads reads) { this.reads = reads; }

    public ScopeSnapshot resolve(String authorization, DashboardActor actor, Scope dashboard, Scope runtime,
            Scope widget, Map<Scope, ScopeSnapshot> memo) {
        return resolve(authorization, actor, dashboard, runtime, widget, memo, false);
    }
    public ScopeSnapshot resolve(String authorization, DashboardActor actor, Scope dashboard, Scope runtime,
            Scope widget, Map<Scope, ScopeSnapshot> memo, boolean enforceRuntime) {
        if (dashboard.workspaceId()!=null) {
            runtime=collaboration(runtime,dashboard);
            widget=collaboration(widget,dashboard);
        }
        // Start with the narrowest explicit binding, so a fixed one-device panel does not read the whole fleet.
        Scope start = widget.mode() == ScopeMode.FIXED || widget.mode() == ScopeMode.UNBOUND ? widget
                : !runtime.deviceIds().isEmpty() ? runtime : !widget.deviceIds().isEmpty() ? widget
                : runtime.mode() != ScopeMode.ALL ? runtime : widget.mode() != ScopeMode.ALL ? widget : dashboard;
        ScopeSnapshot snapshot = load(authorization, actor, start, memo);
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        snapshot.devices().forEach(device -> ids.add(device.id()));
        for (Scope restriction : List.of(dashboard, widget.mode() == ScopeMode.FIXED && !enforceRuntime ? Scope.all() : runtime, widget)) {
            if (restriction.equals(start) || restriction.mode() == ScopeMode.ALL) continue;
            if (restriction.mode() == ScopeMode.UNBOUND) { ids.clear(); continue; }
            var candidates = new java.util.TreeSet<>(ids);
            if (!restriction.deviceIds().isEmpty()) candidates.retainAll(restriction.deviceIds());
            if (candidates.isEmpty()) { ids.clear(); continue; }
            Scope narrowed = new Scope(ScopeMode.FIXED, candidates, restriction.locationIds(), restriction.includeSubtree(),
                    restriction.deviceTypes(), restriction.locationTags(),start.workspaceId(),start.accountId());
            ScopeSnapshot allowed = load(authorization, actor, narrowed, memo);
            var allowedIds = allowed.devices().stream().map(DashboardEvidenceReads.Device::id).collect(java.util.stream.Collectors.toSet());
            ids.retainAll(allowedIds);
            if (allowed.deniedBindingCount() > 0) throw new DashboardFailure("NO_ACCESS", "Resource binding is no longer accessible");
        }
        var devices = snapshot.devices().stream().filter(device -> ids.contains(device.id())).toList();
        return new ScopeSnapshot(actor.userId(), snapshot.accountId(), snapshot.workspaceId()==null?snapshot.requestedCount():devices.size(), devices.size(),
                snapshot.deniedBindingCount(), devices, snapshot.locations(), snapshot.deviceTagsSupported(), snapshot.resolvedAt(),snapshot.workspaceId(),snapshot.authorizationFingerprint());
    }
    private Scope collaboration(Scope value,Scope dashboard) {
        return new Scope(value.mode(),value.deviceIds(),value.locationIds(),value.includeSubtree(),value.deviceTypes(),value.locationTags(),dashboard.workspaceId(),dashboard.accountId());
    }
    public ScopeSnapshot capabilities(String authorization,DashboardActor actor,ScopeSnapshot snapshot,String type) {
        if (snapshot.workspaceId()==null) return snapshot;
        java.util.Set<UUID> ids=snapshot.devices().stream().map(DashboardEvidenceReads.Device::id).collect(java.util.stream.Collectors.toSet());
        java.util.List<String> actions=switch (type) {
            case "incident-list" -> java.util.List.of("incident.read");
            case "maintenance-agenda" -> java.util.List.of("maintenance.read");
            case "risk-list" -> java.util.List.of("analytics.read");
            case "prediction-evidence" -> java.util.List.of("analytics.read","ml.read");
            case "estimated-cost" -> java.util.List.of("telemetry.read","tariff.read");
            case "uptime-or-cycle" -> java.util.List.of("telemetry.read","operational.read");
            case "quality-and-freshness" -> java.util.List.of("telemetry.read","analytics.read");
            case "location-matrix" -> java.util.List.of("telemetry.read","analytics.read","incident.read");
            case "context-note" -> java.util.List.of();
            default -> java.util.List.of("telemetry.read");
        };
        StringBuilder fingerprint=new StringBuilder(String.valueOf(snapshot.authorizationFingerprint()));
        for (String action:actions) {
            Scope scope=new Scope(ids.isEmpty()?ScopeMode.UNBOUND:ScopeMode.FIXED,ids,java.util.Set.of(),false,java.util.Set.of(),java.util.Set.of(),snapshot.workspaceId(),snapshot.accountId());
            var allowed=reads.scopeForAction(authorization,actor,scope,action);
            ids.retainAll(allowed.devices().stream().map(DashboardEvidenceReads.Device::id).collect(java.util.stream.Collectors.toSet()));
            fingerprint.append(':').append(allowed.authorizationFingerprint());
        }
        if(ids.isEmpty() && !"context-note".equals(type)) throw new DashboardFailure("NO_ACCESS","Current resource capabilities are unavailable");
        var devices=snapshot.devices().stream().filter(device->ids.contains(device.id())).toList();
        var locations=devices.stream().map(DashboardEvidenceReads.Device::locationId).collect(java.util.stream.Collectors.toSet());
        return new ScopeSnapshot(actor.userId(),snapshot.accountId(),devices.size(),devices.size(),0,devices,snapshot.locations().stream().filter(location->locations.contains(location.id())).toList(),false,snapshot.resolvedAt(),snapshot.workspaceId(),fingerprint.toString());
    }

    private ScopeSnapshot load(String authorization, DashboardActor actor, Scope scope, Map<Scope, ScopeSnapshot> memo) {
        return memo.computeIfAbsent(scope, ignored -> {
            ScopeSnapshot result = reads.scope(authorization, actor, scope);
            if (!actor.userId().equals(result.userId()) || !(scope.workspaceId()==null?actor.accountId():scope.accountId()).equals(result.accountId())
                    || result.devices().size() != result.evaluatedCount()
                    || result.devices().stream().map(DashboardEvidenceReads.Device::id).distinct().count() != result.devices().size()) {
                throw new DashboardFailure("INVALID_READ_RESPONSE", "Owner scope response is inconsistent");
            }
            if (result.evaluatedCount() > 500) throw new DashboardFailure("LIMIT_EXCEEDED", "Scope exceeds 500 devices");
            return result;
        });
    }
}
