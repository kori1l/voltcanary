package com.voltcanary.dashboard.application;

import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Draft;
import com.voltcanary.dashboard.domain.DashboardRecords.Revision;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardDrafts {

    private final DashboardStorage storage;
    private final DashboardAccess access;
    private final DefinitionValidation validation;
    private final DefinitionBindings bindings;
    private final DashboardIdempotency idempotency;

    @Transactional
    public Draft get(DashboardActor actor, UUID id) {
        access.capability(actor, id, "EDIT");
        Draft draft = storage.draft(id, actor.userId());
        return new Draft(
            id,
            actor.userId(),
            draft.baseRevision(),
            draft.etag(),
            access.context(id, draft.definition()),
            draft.updatedAt()
        );
    }

    @Transactional
    public UUID lifecycleEtag(DashboardActor actor, UUID id) {
        access.capability(actor, id, "ARCHIVE");
        return storage.draft(id, actor.userId()).etag();
    }

    @Transactional
    public Draft save(
        DashboardActor actor,
        String authorization,
        UUID id,
        String ifMatch,
        int baseRevision,
        DashboardDefinition definition,
        Integer sourceRevision
    ) {
        storage.lockActor(actor);
        var dashboard = access.editable(actor, id);
        Draft current = storage.draft(id, actor.userId());
        access.match(ifMatch, current.etag());
        if (baseRevision != dashboard.publishedRevision()) {
            throw new DashboardFailure(
                "CONFLICT",
                "Published revision changed; compare before saving"
            );
        }
        DashboardDefinition chosen = access.context(
            id,
            sourceRevision == null ? definition : revision(actor, id, sourceRevision).definition()
        );
        validation.validate(chosen);
        bindings.requireAuthorized(authorization, actor, chosen);
        Draft saved = new Draft(
            id,
            actor.userId(),
            baseRevision,
            UUID.randomUUID(),
            chosen,
            Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        );
        storage.saveDraft(saved);
        storage.audit(
            actor,
            id,
            baseRevision,
            sourceRevision == null ? "DRAFT_SAVED" : "ROLLBACK_DRAFT",
            saved.updatedAt()
        );
        return saved;
    }

    /** Optional receipt for assistant/other clients; ordinary editor saves retain their existing contract. */
    @Transactional
    public Draft saveReviewed(
        DashboardActor actor,
        String authorization,
        UUID id,
        String ifMatch,
        int baseRevision,
        DashboardDefinition definition,
        Integer sourceRevision,
        String key,
        com.fasterxml.jackson.databind.JsonNode request
    ) {
        access.capability(actor, id, "EDIT");
        return idempotency.execute(
            actor,
            "/dashboards/" + id + "/draft",
            key,
            request,
            Draft.class,
            saved -> {
                access.capability(actor, saved.dashboardId(), "EDIT");
                bindings.requireAuthorized(authorization, actor, saved.definition());
            },
            () -> save(actor, authorization, id, ifMatch, baseRevision, definition, sourceRevision)
        );
    }

    @Transactional(readOnly = true)
    public Draft receipt(DashboardActor actor, String authorization, UUID id, String key) {
        access.capability(actor, id, "EDIT");
        return idempotency
            .lookup(actor, "/dashboards/" + id + "/draft", key, Draft.class, saved -> {
                access.capability(actor, saved.dashboardId(), "EDIT");
                bindings.requireAuthorized(authorization, actor, saved.definition());
            })
            .orElseThrow(() -> new DashboardFailure("NOT_FOUND", "Draft update receipt not found"));
    }

    @Transactional(readOnly = true)
    public Revision revision(DashboardActor actor, UUID id, int revision) {
        access.capability(actor, id, "HISTORY");
        return storage
            .revision(id, revision)
            .orElseThrow(() -> new DashboardFailure("NOT_FOUND", "Dashboard revision not found"));
    }

    @Transactional(readOnly = true)
    public List<Revision> revisions(DashboardActor actor, UUID id, int before, int limit) {
        access.capability(actor, id, "HISTORY");
        if (before < 1 || limit < 1 || limit > 100) {
            throw new DashboardFailure(
                "VALIDATION_ERROR",
                "Invalid revision cursor or limit",
                "limit"
            );
        }
        return storage.revisions(id, before, limit + 1);
    }
}
