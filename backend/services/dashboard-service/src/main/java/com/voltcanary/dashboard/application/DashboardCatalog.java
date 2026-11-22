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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardCatalog {

    private final DashboardStorage storage;
    private final DashboardAccess access;
    private final DefinitionValidation validation;
    private final DefinitionBindings bindings;
    private final DashboardIdempotency idempotency;

    @Transactional
    public Dashboard create(
        DashboardActor actor,
        String authorization,
        String key,
        JsonNode body,
        DashboardDefinition definition
    ) {
        validation.validate(definition);
        bindings.requireAuthorized(authorization, actor, definition);
        return idempotency.execute(
            actor,
            "/dashboards",
            key,
            body,
            Dashboard.class,
            saved -> access.owned(actor, saved.id()),
            () -> insert(actor, definition, "CREATED")
        );
    }

    @Transactional(readOnly = true)
    public List<Dashboard> list(DashboardActor actor, String status, Cursor cursor, int limit) {
        if (!List.of("ACTIVE", "ARCHIVED", "ALL").contains(status) || limit < 1 || limit > 100) {
            throw new DashboardFailure(
                "VALIDATION_ERROR",
                "Invalid list status or page limit",
                "limit"
            );
        }
        return storage.list(actor, status, cursor, limit + 1);
    }

    @Transactional(readOnly = true)
    public Dashboard get(DashboardActor actor, UUID id) {
        return access.owned(actor, id);
    }

    @Transactional(readOnly = true)
    public Dashboard creationReceipt(DashboardActor actor, String key) {
        return idempotency
            .lookup(actor, "/dashboards", key, Dashboard.class, saved ->
                access.owned(actor, saved.id())
            )
            .orElseThrow(() ->
                new DashboardFailure("NOT_FOUND", "Creation receipt is unavailable")
            );
    }

    public java.util.List<String> permissions(DashboardActor actor, UUID id) {
        return access.permissions(actor, id).stream().map(String::toLowerCase).toList();
    }

    public DashboardDefinition definition(DashboardActor actor, UUID id, int revision) {
        var dashboard = access.owned(actor, id);
        if (revision != dashboard.publishedRevision()) access.capability(actor, id, "HISTORY");
        return storage
            .revision(id, revision)
            .orElseThrow(() -> new DashboardFailure("NOT_FOUND", "Revision unavailable"))
            .definition();
    }

    @Transactional
    public Dashboard copy(
        DashboardActor actor,
        String authorization,
        UUID id,
        String key,
        JsonNode body,
        String name
    ) {
        access.capability(actor, id, "COPY");
        return idempotency.execute(
            actor,
            "/dashboards/" + id + "/copy",
            key,
            body,
            Dashboard.class,
            saved -> {
                access.capability(actor, id, "COPY");
                access.owned(actor, saved.id());
            },
            () -> {
                Dashboard source = access.capability(actor, id, "COPY");
                if (
                    source.publishedRevision() == 0 && !source.ownerUserId().equals(actor.userId())
                ) throw new DashboardFailure(
                    "NO_ACCESS",
                    "Publish an initial revision before sharing a copy"
                );
                DashboardDefinition original =
                    source.publishedRevision() > 0
                        ? storage
                              .revision(id, source.publishedRevision())
                              .orElseThrow()
                              .definition()
                        : storage.draft(id).definition();
                DashboardDefinition safe = source.ownerUserId().equals(actor.userId())
                    ? bindings.safeCopy(authorization, actor, original)
                    : bindings.template(original);
                DashboardDefinition copied = new DashboardDefinition(
                    safe.schemaVersion(),
                    name,
                    safe.description(),
                    safe.defaultContext(),
                    safe.sections(),
                    safe.widgets(),
                    safe.layouts(),
                    safe.screens()
                );
                validation.validate(copied);
                return insert(actor, copied, "COPIED");
            }
        );
    }

    private Dashboard insert(DashboardActor actor, DashboardDefinition definition, String action) {
        access.quota(actor);
        Instant now = Instant.now();
        Dashboard dashboard = new Dashboard(
            UUID.randomUUID(),
            actor.userId(),
            actor.accountId(),
            definition.name(),
            definition.description(),
            "ACTIVE",
            0,
            definition.schemaVersion(),
            now,
            now
        );
        storage.insert(dashboard);
        storage.saveDraft(
            new Draft(dashboard.id(), actor.userId(), 0, UUID.randomUUID(), definition, now)
        );
        storage.audit(actor, dashboard.id(), 0, action, now);
        return dashboard;
    }
}
