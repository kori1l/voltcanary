package com.voltcanary.dashboard.port;

import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardRecords.Cursor;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.domain.DashboardRecords.Draft;
import com.voltcanary.dashboard.domain.DashboardRecords.Idempotency;
import com.voltcanary.dashboard.domain.DashboardRecords.Preferences;
import com.voltcanary.dashboard.domain.DashboardRecords.Revision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DashboardStorage {
    void lockActor(DashboardActor actor);
    long activeCount(DashboardActor actor);
    Optional<Dashboard> findOwned(DashboardActor actor, UUID id);
    List<Dashboard> list(DashboardActor actor, String status, Cursor cursor, int limit);
    void insert(Dashboard dashboard);
    void update(Dashboard dashboard);
    Draft draft(UUID id);
    void saveDraft(Draft draft);
    void appendRevision(Revision revision);
    Optional<Revision> revision(UUID id, int revision);
    List<Revision> revisions(UUID id, int before, int limit);
    Optional<Preferences> preferences(DashboardActor actor);
    void savePreferences(DashboardActor actor, Preferences preferences);
    Optional<Idempotency> idempotency(DashboardActor actor, String route, String keyHash, Instant now);
    void saveIdempotency(DashboardActor actor, String route, String keyHash, Idempotency outcome);
    void audit(DashboardActor actor, UUID id, int revision, String action, Instant at);
}
