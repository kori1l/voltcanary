package com.voltcanary.dashboard.query;

import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.query.QueryTypes.Batch;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small process-local result cache; callers must renew current owner authority before lookup. */
final class DashboardQueryCache {

    private record Cached(DashboardActor actor, Instant until, Batch batch, int bytes) {}

    private final Map<String, Cached> entries = new LinkedHashMap<>(16, 0.75f, true);

    synchronized Batch find(
        String key,
        DashboardActor actor,
        Instant now,
        boolean safe,
        boolean forceRefresh
    ) {
        entries.entrySet().removeIf(entry -> !entry.getValue().until().isAfter(now));
        if (!safe) {
            invalidate(actor);
            return null;
        }
        if (forceRefresh) return null;
        Cached hit = entries.get(key);
        if (hit == null || !hit.actor().equals(actor)) return null;
        Batch batch = hit.batch();
        return new Batch(
            batch.dashboardId(),
            batch.revision(),
            batch.draftEtag(),
            batch.contextHash(),
            batch.asOf(),
            Instant.now(),
            true,
            batch.panels(),
            batch.scopes()
        );
    }

    synchronized void invalidate(DashboardActor actor) {
        entries.entrySet().removeIf(entry -> entry.getValue().actor().equals(actor));
    }

    synchronized void remember(
        String key,
        DashboardActor actor,
        Instant asOf,
        Batch wire,
        int bytes
    ) {
        if (
            bytes > 8_000_000 ||
            wire
                .panels()
                .stream()
                .anyMatch(
                    panel -> !"ready".equals(panel.status()) && !"no_data".equals(panel.status())
                )
        ) return;
        entries.remove(key);
        while (
            !entries.isEmpty() &&
            (entries.size() >= 64 ||
                entries.values().stream().mapToLong(Cached::bytes).sum() + bytes > 16_000_000)
        ) {
            entries.remove(entries.keySet().iterator().next());
        }
        entries.put(key, new Cached(actor, asOf.plusSeconds(30), wire, bytes));
    }
}
