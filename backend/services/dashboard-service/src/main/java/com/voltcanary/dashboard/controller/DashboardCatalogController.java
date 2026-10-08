package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.application.DashboardCatalog;
import com.voltcanary.dashboard.application.DashboardDrafts;
import com.voltcanary.dashboard.application.DashboardLifecycle;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Cursor;
import com.voltcanary.dashboard.domain.DashboardRecords.Dashboard;
import com.voltcanary.dashboard.port.DashboardOwnership;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboards")
public class DashboardCatalogController {
    public record CatalogPage(List<Dashboard> items, boolean hasNext, String nextCursor) {
    }

    public record DashboardView(Dashboard dashboard, DashboardDefinition definition, int revision,
            List<String> permissions, UUID draftEtag, List<String> unsupportedWidgetIds) {
    }

    private final DashboardOwnership ownership;
    private final DashboardCatalog catalog;
    private final DashboardDrafts drafts;
    private final DashboardLifecycle lifecycle;
    private final DashboardRequestDecoder decoder;
    private final com.voltcanary.dashboard.query.WidgetManifests manifests;

    public DashboardCatalogController(DashboardOwnership ownership, DashboardCatalog catalog,
            DashboardDrafts drafts, DashboardLifecycle lifecycle, DashboardRequestDecoder decoder,
            com.voltcanary.dashboard.query.WidgetManifests manifests) {
        this.ownership = ownership;
        this.catalog = catalog;
        this.drafts = drafts;
        this.lifecycle = lifecycle;
        this.decoder = decoder;
        this.manifests = manifests;
    }

    @GetMapping
    public CatalogPage list(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "status", defaultValue = "ACTIVE") String status, @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        var actor = ownership.resolve(authorization);
        List<Dashboard> loaded = catalog.list(actor, status, cursor(cursor), limit);
        boolean hasNext = loaded.size() > limit;
        List<Dashboard> visible = hasNext ? loaded.subList(0, limit) : loaded;
        Dashboard last = visible.isEmpty() ? null : visible.getLast();
        String next = hasNext && last != null ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (last.createdAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new CatalogPage(visible, hasNext, next);
    }

    @PostMapping
    public ResponseEntity<Dashboard> create(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        var actor = ownership.resolve(authorization);
        return ResponseEntity.status(201).body(catalog.create(actor, authorization, key, body, decoder.definition(body)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DashboardView> get(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @RequestParam(value = "revision", required = false) Integer revision) {
        var actor = ownership.resolve(authorization);
        Dashboard dashboard = catalog.get(actor, id);
        int requested = revision == null ? dashboard.publishedRevision() : revision;
        DashboardDefinition definition = requested == 0 ? null : drafts.revision(actor, id, requested).definition();
        UUID draftEtag = drafts.get(actor, id).etag();
        List<String> permissions = "ACTIVE".equals(dashboard.status())
                ? List.of("view", "edit", "publish", "archive", "copy") : List.of("view", "restore", "copy");
        return ResponseEntity.ok().eTag(id + "-r" + requested + "-" + dashboard.status())
                .body(new DashboardView(dashboard, definition, requested, permissions, draftEtag,
                        definition == null ? List.of() : definition.widgets().stream()
                                .filter(widget -> manifests.find(widget.type(), widget.typeVersion()).map(manifest -> !manifest.supported()).orElse(true))
                                .map(DashboardDefinition.Widget::id).toList()));
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<Dashboard> copy(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        var actor = ownership.resolve(authorization);
        decoder.validateRequest(body);
        return ResponseEntity.status(201).body(catalog.copy(actor, authorization, id, key, body, body.path("name").asText()));
    }

    @PostMapping("/{id}/archive")
    public Dashboard archive(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return lifecycle.transition(ownership.resolve(authorization), id, ifMatch, true);
    }

    @PostMapping("/{id}/restore")
    public Dashboard restore(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return lifecycle.transition(ownership.resolve(authorization), id, ifMatch, false);
    }

    private Cursor cursor(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (value.length() > 160) {
                throw new IllegalArgumentException();
            }
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new DashboardFailure("VALIDATION_ERROR", "Invalid catalog cursor", "cursor");
        }
    }
}
