package com.voltcanary.dashboard.application;

import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DashboardAccess {
    private final DashboardStorage storage;

    public DashboardAccess(DashboardStorage storage) {
        this.storage = storage;
    }

    public Dashboard owned(DashboardActor actor, UUID id) {
        return storage.findOwned(actor, id)
                .orElseThrow(() -> new DashboardFailure("NOT_FOUND", "Dashboard not found"));
    }

    public Dashboard editable(DashboardActor actor, UUID id) {
        Dashboard dashboard = owned(actor, id);
        if (!"ACTIVE".equals(dashboard.status())) {
            throw new DashboardFailure("CONFLICT", "Restore the dashboard before editing");
        }
        return dashboard;
    }

    public void quota(DashboardActor actor) {
        if (storage.activeCount(actor) >= 10) {
            throw new DashboardFailure("QUOTA_EXCEEDED", "At most ten active dashboards are allowed");
        }
    }

    public void match(String supplied, UUID current) {
        if (supplied == null || supplied.isBlank()) {
            throw new DashboardFailure("PRECONDITION_REQUIRED", "If-Match is required");
        }
        if (!supplied.equals('"' + current.toString() + '"')) {
            throw new DashboardFailure("CONFLICT", "Draft changed; compare the current draft before saving");
        }
    }
}
