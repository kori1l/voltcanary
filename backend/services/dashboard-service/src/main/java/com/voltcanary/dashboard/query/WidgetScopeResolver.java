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
        // Start with the narrowest explicit binding, so a fixed one-device panel does not read the whole fleet.
        Scope start = widget.mode() == ScopeMode.FIXED || widget.mode() == ScopeMode.UNBOUND ? widget
                : !runtime.deviceIds().isEmpty() ? runtime : !widget.deviceIds().isEmpty() ? widget
                : runtime.mode() != ScopeMode.ALL ? runtime : widget.mode() != ScopeMode.ALL ? widget : dashboard;
        ScopeSnapshot snapshot = load(authorization, actor, start, memo);
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        snapshot.devices().forEach(device -> ids.add(device.id()));
        for (Scope restriction : List.of(dashboard, widget.mode() == ScopeMode.FIXED ? Scope.all() : runtime, widget)) {
            if (restriction.equals(start) || restriction.mode() == ScopeMode.ALL) continue;
            if (restriction.mode() == ScopeMode.UNBOUND) { ids.clear(); continue; }
            var candidates = new java.util.TreeSet<>(ids);
            if (!restriction.deviceIds().isEmpty()) candidates.retainAll(restriction.deviceIds());
            if (candidates.isEmpty()) { ids.clear(); continue; }
            Scope narrowed = new Scope(ScopeMode.FIXED, candidates, restriction.locationIds(), restriction.includeSubtree(),
                    restriction.deviceTypes(), restriction.locationTags());
            ScopeSnapshot allowed = load(authorization, actor, narrowed, memo);
            var allowedIds = allowed.devices().stream().map(DashboardEvidenceReads.Device::id).collect(java.util.stream.Collectors.toSet());
            ids.retainAll(allowedIds);
            if (allowed.deniedBindingCount() > 0) throw new DashboardFailure("NO_ACCESS", "Resource binding is no longer accessible");
        }
        var devices = snapshot.devices().stream().filter(device -> ids.contains(device.id())).toList();
        return new ScopeSnapshot(actor.userId(), actor.accountId(), snapshot.requestedCount(), devices.size(),
                snapshot.deniedBindingCount(), devices, snapshot.locations(), snapshot.deviceTagsSupported(), snapshot.resolvedAt());
    }

    private ScopeSnapshot load(String authorization, DashboardActor actor, Scope scope, Map<Scope, ScopeSnapshot> memo) {
        return memo.computeIfAbsent(scope, ignored -> {
            ScopeSnapshot result = reads.scope(authorization, actor, scope);
            if (!actor.userId().equals(result.userId()) || !actor.accountId().equals(result.accountId())
                    || result.devices().size() != result.evaluatedCount()
                    || result.devices().stream().map(DashboardEvidenceReads.Device::id).distinct().count() != result.devices().size()) {
                throw new DashboardFailure("INVALID_READ_RESPONSE", "Owner scope response is inconsistent");
            }
            if (result.evaluatedCount() > 500) throw new DashboardFailure("LIMIT_EXCEEDED", "Scope exceeds 500 devices");
            return result;
        });
    }
}
