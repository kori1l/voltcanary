package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.application.DashboardDrafts;
import com.voltcanary.dashboard.application.DashboardPublication;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Draft;
import com.voltcanary.dashboard.domain.DashboardRecords.Revision;
import com.voltcanary.dashboard.port.DashboardOwnership;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboards/{id}")
public class DashboardDraftController {
    public record RevisionMetadata(int revision, UUID publishedBy, Instant publishedAt) {
    }

    public record RevisionPage(List<RevisionMetadata> items, boolean hasNext, Integer nextBefore) {
    }

    private final DashboardOwnership ownership;
    private final DashboardDrafts drafts;
    private final DashboardPublication publication;
    private final DashboardRequestDecoder decoder;
    private final DefinitionValidation validation;

    public DashboardDraftController(DashboardOwnership ownership, DashboardDrafts drafts,
            DashboardPublication publication, DashboardRequestDecoder decoder, DefinitionValidation validation) {
        this.ownership = ownership;
        this.drafts = drafts;
        this.publication = publication;
        this.decoder = decoder;
        this.validation = validation;
    }

    @GetMapping("/draft")
    public ResponseEntity<Draft> get(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id) {
        Draft draft = drafts.get(ownership.resolve(authorization), id);
        return ResponseEntity.ok().eTag(draft.etag().toString()).body(draft);
    }

    @PutMapping("/draft")
    public ResponseEntity<Draft> save(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody JsonNode body) {
        var actor = ownership.resolve(authorization);
        validation.validatePayload(body);
        int base = decoder.nonnegative(body, "baseRevision");
        Integer source = body.hasNonNull("sourceRevision") ? decoder.nonnegative(body, "sourceRevision") : null;
        if (source != null && body.hasNonNull("definition")) {
            throw new DashboardFailure("VALIDATION_ERROR", "Choose definition or sourceRevision, not both", "sourceRevision");
        }
        Draft saved = drafts.save(actor, authorization, id, ifMatch, base,
                source == null ? decoder.definition(body) : null, source);
        return ResponseEntity.ok().eTag(saved.etag().toString()).body(saved);
    }

    @PostMapping("/publish")
    public ResponseEntity<Revision> publish(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        var actor = ownership.resolve(authorization);
        validation.validatePayload(body);
        Revision saved = publication.publish(actor, authorization, id, ifMatch, key, body,
                decoder.uuid(body, "draftEtag", false));
        return ResponseEntity.ok().eTag(id + "-r" + saved.revision()).body(saved);
    }

    @GetMapping("/revisions")
    public RevisionPage revisions(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id, @RequestParam(value = "before", defaultValue = "2147483647") int before,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        var loaded = drafts.revisions(ownership.resolve(authorization), id, before, limit);
        boolean next = loaded.size() > limit;
        var visible = next ? loaded.subList(0, limit) : loaded;
        return new RevisionPage(visible.stream().map(revision -> new RevisionMetadata(revision.revision(),
                revision.publishedBy(), revision.publishedAt())).toList(), next,
                next ? visible.getLast().revision() : null);
    }
}
