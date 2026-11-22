package com.voltcanary.dashboard.application;

import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DashboardAccess {

    private final DashboardStorage storage;
    private final com.voltcanary.dashboard.sharing.DashboardSharing sharing;

    public Dashboard owned(DashboardActor actor, UUID id) {
        if (sharing != null && sharing.shared(id)) return sharing.require(actor, id, "VIEW");
        return storage
            .findOwned(actor, id)
            .orElseThrow(() -> new DashboardFailure("NOT_FOUND", "Dashboard not found"));
    }

    public Dashboard editable(DashboardActor actor, UUID id) {
        Dashboard dashboard = capability(actor, id, "EDIT");
        if (!"ACTIVE".equals(dashboard.status())) {
            throw new DashboardFailure("CONFLICT", "Restore the dashboard before editing");
        }
        return dashboard;
    }

    public Dashboard capability(DashboardActor actor, UUID id, String capability) {
        if (sharing != null && sharing.shared(id)) return sharing.require(actor, id, capability);
        return owned(actor, id);
    }

    public java.util.List<String> permissions(DashboardActor actor, UUID id) {
        var dashboard = owned(actor, id);
        return sharing == null
            ? com.voltcanary.dashboard.sharing.DashboardSharing.CAPABILITIES.stream()
                  .sorted()
                  .toList()
            : sharing.permissions(actor, dashboard);
    }

    public com.voltcanary.dashboard.domain.DashboardDefinition context(
        UUID id,
        com.voltcanary.dashboard.domain.DashboardDefinition definition
    ) {
        return sharing == null ? definition : sharing.context(id, definition);
    }

    public void quota(DashboardActor actor) {
        int limit = storage.activeLimit(actor);
        if (storage.activeCount(actor) >= limit) {
            throw new DashboardFailure(
                "QUOTA_EXCEEDED",
                "At most " + limit + " active dashboards are allowed"
            );
        }
    }

    public void match(String supplied, UUID current) {
        if (supplied == null || supplied.isBlank()) {
            throw new DashboardFailure("PRECONDITION_REQUIRED", "If-Match is required");
        }
        if (!supplied.equals('"' + current.toString() + '"')) {
            throw new DashboardFailure(
                "CONFLICT",
                "Draft changed; compare the current draft before saving"
            );
        }
    }
}
