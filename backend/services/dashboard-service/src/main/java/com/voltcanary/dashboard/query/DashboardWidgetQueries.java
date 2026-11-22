package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.query.QueryTypes.Batch;
import com.voltcanary.dashboard.query.QueryTypes.Panel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Service
public class DashboardWidgetQueries {

    public record Request(
        Integer revision,
        UUID draftEtag,
        JsonNode runtimeContext,
        boolean forceRefresh,
        List<String> widgetIds
    ) {
        public Request(
            Integer revision,
            UUID draftEtag,
            JsonNode runtimeContext,
            boolean forceRefresh
        ) {
            this(revision, draftEtag, runtimeContext, forceRefresh, null);
        }
    }

    private final QueryDefinitionReader definitions;
    private final ObjectMapper mapper;
    private final CompactDashboardEvidence compact;
    private final WidgetQueryPreparation preparation;
    private final DashboardEvidenceExecution execution;
    private final DashboardQueryCache cache = new DashboardQueryCache();

    public DashboardWidgetQueries(
        QueryDefinitionReader definitions,
        WidgetManifests manifests,
        WidgetQueryDecoder decoder,
        WidgetScopeResolver scopes,
        QueryTimeResolver time,
        QueryReadScheduler scheduler,
        WidgetEvidenceProjection projection,
        ObjectMapper mapper
    ) {
        this(
            definitions,
            manifests,
            decoder,
            scopes,
            time,
            scheduler,
            projection,
            mapper,
            new CompactDashboardEvidence(mapper)
        );
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardWidgetQueries(
        QueryDefinitionReader definitions,
        WidgetManifests manifests,
        WidgetQueryDecoder decoder,
        WidgetScopeResolver scopes,
        QueryTimeResolver time,
        QueryReadScheduler scheduler,
        WidgetEvidenceProjection projection,
        ObjectMapper mapper,
        CompactDashboardEvidence compact
    ) {
        this.definitions = definitions;
        this.mapper = mapper;
        this.compact = compact;
        this.preparation = new WidgetQueryPreparation(
            manifests,
            decoder,
            scopes,
            time,
            scheduler,
            projection,
            mapper
        );
        this.execution = new DashboardEvidenceExecution(
            manifests,
            scheduler,
            projection,
            mapper,
            compact
        );
    }

    public Batch query(String authorization, DashboardActor actor, UUID id, Request request) {
        return query(authorization, actor, id, request, false);
    }

    public Batch query(
        String authorization,
        DashboardActor actor,
        UUID id,
        Request request,
        boolean validateOnly
    ) {
        return query(authorization, actor, id, request, validateOnly, null);
    }

    public Batch query(
        String authorization,
        DashboardActor actor,
        UUID id,
        Request request,
        boolean validateOnly,
        java.util.function.Consumer<Batch> progress
    ) {
        var resolved = definitions.read(actor, id, request);
        DashboardDefinition definition = resolved.definition();
        int revision = resolved.revision();
        return queryDefinition(
            authorization,
            actor,
            id,
            request,
            validateOnly,
            progress,
            definition,
            revision,
            false
        );
    }

    /** Unsaved definitions use the same scope, time, owner reads and evidence projection as saved dashboards. */
    public Batch preview(
        String authorization,
        DashboardActor actor,
        DashboardDefinition definition
    ) {
        UUID id = UUID.nameUUIDFromBytes(
            QueryHash.of(mapper, List.of("unsaved-preview", actor, definition)).getBytes(
                java.nio.charset.StandardCharsets.UTF_8
            )
        );
        return queryDefinition(
            authorization,
            actor,
            id,
            new Request(null, null, null, true),
            false,
            null,
            definition,
            0,
            true
        );
    }

    private Batch queryDefinition(
        String authorization,
        DashboardActor actor,
        UUID id,
        Request request,
        boolean validateOnly,
        java.util.function.Consumer<Batch> progress,
        DashboardDefinition definition,
        int revision,
        boolean unsaved
    ) {
        Instant asOf = Instant.now();
        var prepared = preparation.prepare(
            authorization,
            actor,
            id,
            request,
            definition,
            asOf,
            validateOnly
        );
        if (validateOnly) return execution.validation(actor, id, revision, request, asOf, prepared);
        var context = execution.cacheContext(authorization, actor, prepared);
        String key = QueryHash.of(
            mapper,
            List.of(
                actor,
                id,
                revision,
                request.draftEtag() == null ? "published" : request.draftEtag(),
                definition,
                preparation.runtime(request),
                context.fingerprint(),
                request.widgetIds() == null ? List.of() : request.widgetIds()
            )
        );
        Batch hit = cache.find(key, actor, asOf, context.safe(), request.forceRefresh());
        if (hit != null) {
            if (progress != null) progress.accept(hit);
            return hit;
        }
        String contextHash = QueryHash.of(mapper, List.of(key, asOf));
        Batch batch = execution.query(
            authorization,
            actor,
            id,
            revision,
            request,
            asOf,
            contextHash,
            prepared,
            progress
        );
        if (
            batch
                .panels()
                .stream()
                .anyMatch(panel -> "no_access".equals(panel.status()))
        ) cache.invalidate(actor);
        Batch wire = unsaved ? batch : compact.compact(actor, request, batch);
        int bytes;
        try {
            bytes = mapper.writeValueAsBytes(wire).length;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        if (!unsaved && context.safe()) cache.remember(key, actor, asOf, wire, bytes);
        return wire;
    }

    public Panel snapshotDetails(
        String authorization,
        DashboardActor actor,
        UUID id,
        String hash,
        String widgetId
    ) {
        return compact.details(authorization, actor, id, hash, widgetId, this);
    }
}
