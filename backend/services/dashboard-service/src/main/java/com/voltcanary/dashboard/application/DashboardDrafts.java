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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardDrafts {
    private final DashboardStorage storage;
    private final DashboardAccess access;
    private final DefinitionValidation validation;
    private final DefinitionBindings bindings;

    public DashboardDrafts(DashboardStorage storage, DashboardAccess access,
            DefinitionValidation validation, DefinitionBindings bindings) {
        this.storage = storage;
        this.access = access;
        this.validation = validation;
        this.bindings = bindings;
    }

    @Transactional(readOnly = true)
    public Draft get(DashboardActor actor, UUID id) {
        access.owned(actor, id);
        return storage.draft(id);
    }

    @Transactional
    public Draft save(DashboardActor actor, String authorization, UUID id, String ifMatch,
            int baseRevision, DashboardDefinition definition, Integer sourceRevision) {
        storage.lockActor(actor);
        var dashboard = access.editable(actor, id);
        Draft current = storage.draft(id);
        access.match(ifMatch, current.etag());
        if (baseRevision != dashboard.publishedRevision()) {
            throw new DashboardFailure("CONFLICT", "Published revision changed; compare before saving");
        }
        DashboardDefinition chosen = sourceRevision == null ? definition : revision(actor, id, sourceRevision).definition();
        validation.validate(chosen);
        bindings.requireAuthorized(authorization, actor, chosen);
        Draft saved = new Draft(id, actor.userId(), baseRevision, UUID.randomUUID(), chosen, Instant.now());
        storage.saveDraft(saved);
        storage.audit(actor, id, baseRevision, sourceRevision == null ? "DRAFT_SAVED" : "ROLLBACK_DRAFT", saved.updatedAt());
        return saved;
    }

    @Transactional(readOnly = true)
    public Revision revision(DashboardActor actor, UUID id, int revision) {
        access.owned(actor, id);
        return storage.revision(id, revision)
                .orElseThrow(() -> new DashboardFailure("NOT_FOUND", "Dashboard revision not found"));
    }

    @Transactional(readOnly = true)
    public List<Revision> revisions(DashboardActor actor, UUID id, int before, int limit) {
        access.owned(actor, id);
        if (before < 1 || limit < 1 || limit > 100) {
            throw new DashboardFailure("VALIDATION_ERROR", "Invalid revision cursor or limit", "limit");
        }
        return storage.revisions(id, before, limit + 1);
    }
}
