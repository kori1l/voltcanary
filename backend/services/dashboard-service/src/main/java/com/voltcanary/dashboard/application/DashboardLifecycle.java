package com.voltcanary.dashboard.application;

import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.domain.DashboardRecords.Draft;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardLifecycle {
    private final DashboardStorage storage;
    private final DashboardAccess access;
    private final PersonalDashboardPreferences preferences;

    public DashboardLifecycle(DashboardStorage storage, DashboardAccess access, PersonalDashboardPreferences preferences) {
        this.storage = storage;
        this.access = access;
        this.preferences = preferences;
    }

    @Transactional
    public Dashboard transition(DashboardActor actor, UUID id, String ifMatch, boolean archive) {
        storage.lockActor(actor);
        Dashboard current = access.owned(actor, id);
        Draft draft = storage.draft(id);
        access.match(ifMatch, draft.etag());
        String nextStatus = archive ? "ARCHIVED" : "ACTIVE";
        if (nextStatus.equals(current.status())) {
            return current;
        }
        if (!archive) {
            access.quota(actor);
        }
        Instant now = Instant.now();
        Dashboard updated = new Dashboard(id, actor.userId(), actor.accountId(), current.name(),
                current.description(), nextStatus, current.publishedRevision(), current.schemaVersion(), current.createdAt(), now);
        storage.update(updated);
        storage.saveDraft(new Draft(id, draft.authorUserId(), draft.baseRevision(), UUID.randomUUID(), draft.definition(), now));
        if (archive) {
            preferences.archived(actor, id, now);
        }
        storage.audit(actor, id, current.publishedRevision(), archive ? "ARCHIVED" : "RESTORED", now);
        return updated;
    }
}
