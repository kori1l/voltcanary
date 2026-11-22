package com.voltcanary.device.controller;

import com.voltcanary.device.configurable.application.NativeReviewRequests;
import com.voltcanary.device.configurable.application.SourceBindingDescriptions;
import com.voltcanary.device.configurable.application.SourceMappingConfirmations;
import com.voltcanary.device.configurable.application.SourceMappingPreviews;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclSourceBindingDescription;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SourceBindingsController {

    private final SourceBindingDescriptions descriptions;
    private final AuthenticatedUserResolver users;
    private final com.voltcanary.device.configurable.application.SourceCaptureDescriptions captures;
    private final com.voltcanary.device.configurable.application.SourceObservationReads observations;
    private final SourceMappingPreviews mappings;
    private final SourceMappingConfirmations confirmations;

    @GetMapping("/data/source-bindings/{id}")
    public VclSourceBindingDescription describe(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestParam(required = false) Long schemaRevision
    ) {
        return descriptions.describe(users.resolve(headers), id, schemaRevision);
    }

    @GetMapping("/data/source-bindings/{id}/capture")
    public com.voltcanary.vcl.VclSourceCaptureDescription capture(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestParam long schemaRevision
    ) {
        return captures.describe(users.resolve(headers), id, schemaRevision);
    }

    @org.springframework.web.bind.annotation.PostMapping("/data/source-bindings/{id}/observations")
    public com.voltcanary.vcl.VclObservationPage observations(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @org.springframework.web.bind.annotation.RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        return observations.read(actor, id, com.voltcanary.vcl.VclObservationRead.parse(body));
    }

    @org.springframework.web.bind.annotation.PostMapping(
        "/data/source-bindings/{id}/coverage/{proof}"
    )
    public com.voltcanary.vcl.VclSourceCoverage coverage(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @PathVariable UUID proof,
        @org.springframework.web.bind.annotation.RequestBody byte[] body
    ) {
        return observations.coverage(
            users.resolve(headers),
            id,
            com.voltcanary.vcl.VclObservationReadiness.parsePin(body),
            proof
        );
    }

    @org.springframework.web.bind.annotation.PostMapping(
        "/data/source-bindings/{id}/mapping-preview"
    )
    public SourceMappingPreviews.Preview mappingPreview(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @org.springframework.web.bind.annotation.RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        var request = NativeReviewRequests.closed(
            body,
            Set.of("schemaRevision", "fieldId", "conversion", "sample")
        );
        return mappings.preview(
            actor,
            id,
            mappingRevision(request),
            mappingField(request),
            request.path("conversion"),
            request.path("sample")
        );
    }

    @org.springframework.web.bind.annotation.PostMapping(
        "/data/source-bindings/{id}/mapping-confirmation"
    )
    public SourceMappingConfirmations.Receipt mappingConfirmation(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @org.springframework.web.bind.annotation.RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        var request = NativeReviewRequests.closed(
            body,
            Set.of("schemaRevision", "fieldId", "conversion", "mappingDigest")
        );
        var digest = request.path("mappingDigest").textValue();
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new VclException(
            "REVIEW_DIGEST_INVALID",
            ""
        );
        return confirmations.confirm(
            actor,
            id,
            mappingRevision(request),
            mappingField(request),
            request.path("conversion"),
            digest
        );
    }

    @GetMapping("/data/source-bindings/{id}/mapping-receipts/{digest}")
    public SourceMappingConfirmations.Receipt mappingReceipt(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @PathVariable String digest,
        @RequestParam long schemaRevision
    ) {
        if (
            !digest.matches("[0-9a-f]{64}") ||
            schemaRevision < 1 ||
            schemaRevision > 9_007_199_254_740_991L
        ) {
            throw new VclException("REVIEW_DIGEST_INVALID", "");
        }
        return confirmations.receipt(users.resolve(headers), id, schemaRevision, digest);
    }

    @org.springframework.web.bind.annotation.PostMapping(
        "/data/source-bindings/{id}/mapping-replay"
    )
    public SourceMappingPreviews.Replay mappingReplay(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @org.springframework.web.bind.annotation.RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        var request = NativeReviewRequests.closed(
            body,
            Set.of("schemaRevision", "fieldId", "sample")
        );
        return mappings.replay(
            actor,
            id,
            mappingRevision(request),
            mappingField(request),
            request.path("sample")
        );
    }

    @org.springframework.web.bind.annotation.PostMapping(
        "/data/source-bindings/{id}/mapping-event-replay"
    )
    public SourceMappingPreviews.EventReplay mappingEventReplay(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @org.springframework.web.bind.annotation.RequestBody byte[] body
    ) {
        var actor = users.resolve(headers);
        var request = NativeReviewRequests.closed(
            body,
            Set.of("schemaRevision", "schemaSnapshotId", "metrics")
        );
        UUID snapshot;
        try {
            String raw = request.path("schemaSnapshotId").asText();
            snapshot = UUID.fromString(raw);
            if (!snapshot.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new VclException("TARGET_INVALID", "/schemaSnapshotId");
        }
        return mappings.replayEvent(
            actor,
            id,
            mappingRevision(request),
            snapshot,
            request.path("metrics")
        );
    }

    private static long mappingRevision(com.fasterxml.jackson.databind.JsonNode request) {
        var revision = request.path("schemaRevision");
        if (!revision.isIntegralNumber() || revision.longValue() < 1) throw new VclException(
            "BINDING_REVISION_INVALID",
            ""
        );
        return revision.longValue();
    }

    private static UUID mappingField(com.fasterxml.jackson.databind.JsonNode request) {
        String field = request.path("fieldId").asText();
        UUID fieldId;
        try {
            fieldId = UUID.fromString(field);
            if (!fieldId.toString().equalsIgnoreCase(field)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new VclException("FIELD_KEYS_INVALID", "");
        }
        return fieldId;
    }
}
