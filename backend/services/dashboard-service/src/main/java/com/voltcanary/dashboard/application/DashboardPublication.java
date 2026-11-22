package com.voltcanary.dashboard.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.domain.DashboardRecords.Draft;
import com.voltcanary.dashboard.domain.DashboardRecords.Revision;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardPublication {

    private final DashboardStorage storage;
    private final DashboardAccess access;
    private final DefinitionValidation validation;
    private final DefinitionBindings bindings;
    private final DashboardIdempotency idempotency;

    @Transactional
    public Revision publish(
        DashboardActor actor,
        String authorization,
        UUID id,
        String ifMatch,
        String key,
        JsonNode body,
        UUID draftEtag
    ) {
        access.capability(actor, id, "PUBLISH");
        if (draftEtag == null) {
            throw new DashboardFailure("VALIDATION_ERROR", "draftEtag is required", "draftEtag");
        }
        access.match(ifMatch, draftEtag);
        return idempotency.execute(
            actor,
            "/dashboards/" + id + "/publish",
            key,
            body,
            Revision.class,
            saved -> access.capability(actor, id, "PUBLISH"),
            () -> {
                storage.lockDashboard(id);
                Dashboard dashboard = access.capability(actor, id, "PUBLISH");
                if (!"ACTIVE".equals(dashboard.status())) throw new DashboardFailure(
                    "CONFLICT",
                    "Restore dashboard before publication"
                );
                Draft draft = storage.draft(id, actor.userId());
                access.match(ifMatch, draft.etag());
                if (
                    !draft.etag().equals(draftEtag) ||
                    draft.baseRevision() != dashboard.publishedRevision()
                ) {
                    throw new DashboardFailure("CONFLICT", "Draft or published revision changed");
                }
                validation.validate(draft.definition());
                bindings.requireAuthorized(
                    authorization,
                    actor,
                    access.context(id, draft.definition())
                );
                access.capability(actor, id, "PUBLISH");
                Instant now = Instant.now();
                int next = dashboard.publishedRevision() + 1;
                Revision revision = new Revision(id, next, draft.definition(), actor.userId(), now);
                storage.appendRevision(revision);
                storage.update(
                    new Dashboard(
                        id,
                        dashboard.ownerUserId(),
                        dashboard.accountId(),
                        draft.definition().name(),
                        draft.definition().description(),
                        "ACTIVE",
                        next,
                        draft.definition().schemaVersion(),
                        dashboard.createdAt(),
                        now
                    )
                );
                storage.saveDraft(
                    new Draft(id, actor.userId(), next, UUID.randomUUID(), draft.definition(), now)
                );
                storage.audit(actor, id, next, "PUBLISHED", now);
                return revision;
            }
        );
    }
}
