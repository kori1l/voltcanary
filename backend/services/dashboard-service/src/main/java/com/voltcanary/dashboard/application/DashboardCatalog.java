package com.voltcanary.dashboard.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Cursor;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.domain.DashboardRecords.Draft;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardCatalog {
    private final DashboardStorage storage;
    private final DashboardAccess access;
    private final DefinitionValidation validation;
    private final DefinitionBindings bindings;
    private final DashboardIdempotency idempotency;

    public DashboardCatalog(DashboardStorage storage, DashboardAccess access, DefinitionValidation validation,
            DefinitionBindings bindings, DashboardIdempotency idempotency) {
        this.storage = storage;
        this.access = access;
        this.validation = validation;
        this.bindings = bindings;
        this.idempotency = idempotency;
    }

    @Transactional
    public Dashboard create(DashboardActor actor, String authorization, String key, JsonNode body,
            DashboardDefinition definition) {
        validation.validate(definition);
        bindings.requireAuthorized(authorization, actor, definition);
        return idempotency.execute(actor, "/dashboards", key, body, Dashboard.class,
                saved -> access.owned(actor, saved.id()), () -> insert(actor, definition, "CREATED"));
    }

    @Transactional(readOnly = true)
    public List<Dashboard> list(DashboardActor actor, String status, Cursor cursor, int limit) {
        if (!List.of("ACTIVE", "ARCHIVED", "ALL").contains(status) || limit < 1 || limit > 100) {
            throw new DashboardFailure("VALIDATION_ERROR", "Invalid list status or page limit", "limit");
        }
        return storage.list(actor, status, cursor, limit + 1);
    }

    @Transactional(readOnly = true)
    public Dashboard get(DashboardActor actor, UUID id) {
        return access.owned(actor, id);
    }

    @Transactional
    public Dashboard copy(DashboardActor actor, String authorization, UUID id, String key, JsonNode body, String name) {
        access.owned(actor, id);
        return idempotency.execute(actor, "/dashboards/" + id + "/copy", key, body, Dashboard.class,
                saved -> {
                    access.owned(actor, id);
                    access.owned(actor, saved.id());
                }, () -> {
                    Dashboard source = access.owned(actor, id);
                    DashboardDefinition original = source.publishedRevision() > 0
                            ? storage.revision(id, source.publishedRevision()).orElseThrow().definition()
                            : storage.draft(id).definition();
                    DashboardDefinition safe = bindings.safeCopy(authorization, actor, original);
                    DashboardDefinition copied = new DashboardDefinition(safe.schemaVersion(), name, safe.description(),
                            safe.defaultContext(), safe.sections(), safe.widgets(), safe.layouts());
                    validation.validate(copied);
                    return insert(actor, copied, "COPIED");
                });
    }

    private Dashboard insert(DashboardActor actor, DashboardDefinition definition, String action) {
        access.quota(actor);
        Instant now = Instant.now();
        Dashboard dashboard = new Dashboard(UUID.randomUUID(), actor.userId(), actor.accountId(),
                definition.name(), definition.description(), "ACTIVE", 0, definition.schemaVersion(), now, now);
        storage.insert(dashboard);
        storage.saveDraft(new Draft(dashboard.id(), actor.userId(), 0, UUID.randomUUID(), definition, now));
        storage.audit(actor, dashboard.id(), 0, action, now);
        return dashboard;
    }
}
