package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Wire projection and short-lived information snapshots; no public evidence cache. */
@Component
@RequiredArgsConstructor
public class CompactDashboardEvidence {
    private record Snapshot(DashboardActor actor, DashboardWidgetQueries.Request request, QueryTypes.Batch batch, int bytes) { }
    private final ObjectMapper mapper;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();

    public QueryTypes.Batch compact(DashboardActor actor, DashboardWidgetQueries.Request request, QueryTypes.Batch batch) {
        if (!batch.scopes().isEmpty()) return batch;
        Map<String, JsonNode> scopes = new LinkedHashMap<>();
        List<QueryTypes.Panel> panels = new ArrayList<>();
        for (var panel : batch.panels()) {
            if (panel.data() == null) { panels.add(panel); continue; }
            ObjectNode data = mapper.createObjectNode();
            panel.data().fields().forEachRemaining(field -> {
                if (!List.of("devices","locations","sources").contains(field.getKey())) data.set(field.getKey(),field.getValue());
            });
            if (!"context-note".equals(data.path("widgetType").asText())) {
                ObjectNode scope = mapper.createObjectNode();
                scope.set("devices", panel.data().path("devices"));
                scope.set("locations", panel.data().path("locations"));
                String key = QueryHash.of(mapper, scope);
                scopes.putIfAbsent(key, scope);
                data.put("scopeRef", key);
            }
            ObjectNode sources = mapper.createObjectNode();
            panel.data().path("sources").fields().forEachRemaining(entry -> {
                ObjectNode metadata = mapper.createObjectNode();
                entry.getValue().fields().forEachRemaining(field -> {
                    if (List.of("queryAt", "asOf", "intervalBasis", "weighting", "maintenancePolicy", "gapPolicy",
                            "timeBasis", "listTimeBasis", "total", "hasNext", "counts", "byDevice",
                            "measurementQualityCounts", "validationFlagCounts", "validationFlagTimeBasis").contains(field.getKey())
                            || (List.of("incidents", "maintenance").contains(entry.getKey()) && "rows".equals(field.getKey())))
                        metadata.set(field.getKey(), field.getValue());
                });
                sources.set(entry.getKey(), metadata);
            });
            data.set("sources", sources);
            panels.add(new QueryTypes.Panel(panel.widgetId(), panel.status(), data, panel.contextHash(), panel.time(),
                    panel.requestedCount(), panel.evaluatedCount(), panel.receivedAt(), panel.measuredAt(), panel.provenance(), panel.errorCode(), panel.explanation()));
        }
        int bytes;
        try { bytes = mapper.writeValueAsBytes(batch).length; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        synchronized (snapshots) {
            snapshots.entrySet().removeIf(entry -> entry.getValue().batch().asOf().plusSeconds(30).isBefore(Instant.now()));
            Snapshot previous = snapshots.get(batch.contextHash());
            if (previous!=null && previous.actor().equals(actor)) {
                var merged = new LinkedHashMap<String,QueryTypes.Panel>();
                previous.batch().panels().forEach(panel -> merged.put(panel.widgetId(),panel));
                batch.panels().forEach(panel -> merged.put(panel.widgetId(),panel));
                batch = new QueryTypes.Batch(batch.dashboardId(),batch.revision(),batch.draftEtag(),batch.contextHash(),batch.asOf(),batch.returnedAt(),batch.cached(),List.copyOf(merged.values()));
                try { bytes=mapper.writeValueAsBytes(batch).length; }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                snapshots.remove(batch.contextHash());
            }
            if (bytes <= 8_000_000) {
                while (!snapshots.isEmpty() && (snapshots.size() >= 32 || snapshots.values().stream().mapToLong(Snapshot::bytes).sum() + bytes > 32_000_000))
                    snapshots.remove(snapshots.keySet().iterator().next());
                snapshots.put(batch.contextHash(), new Snapshot(actor, request, batch, bytes));
            }
        }
        return new QueryTypes.Batch(batch.dashboardId(), batch.revision(), batch.draftEtag(), batch.contextHash(), batch.asOf(), batch.returnedAt(), batch.cached(), panels, scopes);
    }

    public QueryTypes.Panel details(String authorization, DashboardActor actor, java.util.UUID id, String hash, String widgetId, DashboardWidgetQueries queries) {
        Snapshot snapshot;
        synchronized (snapshots) { snapshot = snapshots.get(hash); }
        if (snapshot == null || !snapshot.actor().equals(actor) || !snapshot.batch().dashboardId().equals(id)
                || snapshot.batch().asOf().plusSeconds(30).isBefore(Instant.now()))
            throw new DashboardFailure("SNAPSHOT_EXPIRED", "Refresh the dashboard before opening source information");
        var request = snapshot.request();
        var renewed = queries.query(authorization, actor, id, new DashboardWidgetQueries.Request(request.revision(), request.draftEtag(), request.runtimeContext(), false, List.of(widgetId)), true);
        var panel = snapshot.batch().panels().stream().filter(value -> value.widgetId().equals(widgetId)).findFirst()
                .orElseThrow(() -> new DashboardFailure("NO_ACCESS", "Panel is unavailable"));
        if (renewed.panels().size() != 1 || !"valid".equals(renewed.panels().getFirst().status())
                || !panel.provenance().path("scopeHash").equals(renewed.panels().getFirst().provenance().path("scopeHash"))) {
            synchronized (snapshots) { snapshots.remove(hash); }
            throw new DashboardFailure("NO_ACCESS", "Snapshot resource access changed");
        }
        return panel;
    }
}
