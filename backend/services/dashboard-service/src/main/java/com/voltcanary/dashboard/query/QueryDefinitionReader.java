package com.voltcanary.dashboard.query;

import com.voltcanary.dashboard.application.DashboardAccess;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolve only an owned immutable revision or the matching current author draft. */
@Component
@RequiredArgsConstructor
public class QueryDefinitionReader {
    public record Resolved(DashboardDefinition definition, int revision) { }
    private final DashboardAccess access;
    private final DashboardStorage storage;

    public Resolved read(DashboardActor actor, UUID id, DashboardWidgetQueries.Request request) {
        var dashboard = access.owned(actor, id);
        if (!"ACTIVE".equals(dashboard.status())) {
            throw new DashboardFailure("CONFLICT", "Archived dashboard is unavailable");
        }
        if (request.revision() != null && request.draftEtag() != null) {
            throw new DashboardFailure("VALIDATION_ERROR", "Choose a published revision or an author draft");
        }
        int revision = request.revision() == null ? dashboard.publishedRevision() : request.revision();
        DashboardDefinition definition;
        if (request.draftEtag() != null) {
            access.capability(actor,id,"EDIT");
            var draft = storage.draft(id,actor.userId());
            if (!actor.userId().equals(draft.authorUserId())) {
                throw new DashboardFailure("NOT_FOUND", "Draft not found");
            }
            if (!request.draftEtag().equals(draft.etag())) {
                throw new DashboardFailure("CONFLICT", "Draft changed");
            }
            definition = draft.definition();
            revision = draft.baseRevision();
        } else {
            if (revision!=dashboard.publishedRevision()) access.capability(actor,id,"HISTORY");
            definition = storage.revision(id, revision)
                    .orElseThrow(() -> new DashboardFailure("NOT_FOUND", "Revision not found")).definition();
        }
        definition=access.context(id,definition);
        DashboardDefinition resolvedDefinition=definition;
        if (definition.widgets().size() > 24) {
            throw new DashboardFailure("LIMIT_EXCEEDED", "At most 24 widgets are allowed");
        }
        if (request.widgetIds() != null && (request.widgetIds().size() > 24
                || request.widgetIds().stream().distinct().count() != request.widgetIds().size()
                || request.widgetIds().stream().anyMatch(widgetId -> resolvedDefinition.widgets().stream()
                        .noneMatch(widget -> widget.id().equals(widgetId))))) {
            throw new DashboardFailure("VALIDATION_ERROR", "Visible widget IDs must belong to the saved definition");
        }
        return new Resolved(definition, revision);
    }
}
