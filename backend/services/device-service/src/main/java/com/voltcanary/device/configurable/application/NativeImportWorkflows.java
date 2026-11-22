package com.voltcanary.device.configurable.application;

import com.voltcanary.device.configurable.domain.NativeImport;
import com.voltcanary.device.configurable.port.NativeImports;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclParser;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NativeImportWorkflows {

    private static final VclParser PARSER = new VclParser();
    private final NativeRowWorkflows rows;
    private final NativeImports imports;

    public NativeImports.Artifact upload(AuthenticatedUser actor, String key, byte[] bytes) {
        return imports.upload(rows.account(actor), actor.id(), key, bytes);
    }

    public NativeImport validate(AuthenticatedUser actor, UUID dataset, byte[] body) {
        var request = NativeReviewRequests.closed(body, Set.of("idempotencyKey", "document"));
        return imports.validate(
            rows.account(actor),
            actor.id(),
            dataset,
            NativeReviewRequests.key(request.path("idempotencyKey")),
            PARSER.parse(request.path("document").toString())
        );
    }

    public NativeImport inspect(AuthenticatedUser actor, UUID dataset, UUID id) {
        return imports.inspect(rows.account(actor), actor.id(), dataset, id);
    }

    public NativeImport apply(AuthenticatedUser actor, UUID dataset, UUID id, byte[] body) {
        var request = NativeReviewRequests.application(body);
        if (request.reviewRevision() != 1) throw new com.voltcanary.vcl.VclException(
            "REVIEW_CONFLICT",
            ""
        );
        return imports.apply(
            rows.account(actor),
            actor.id(),
            dataset,
            id,
            request.reviewDigest(),
            request.idempotencyKey()
        );
    }

    public NativeImport inspectJob(AuthenticatedUser actor, UUID id) {
        return imports.inspectJob(rows.account(actor), actor.id(), id);
    }

    public NativeImport advance(AuthenticatedUser actor, UUID id, byte[] body) {
        var request = NativeReviewRequests.closed(body, Set.of("expectedRevision"));
        var revision = request.path("expectedRevision");
        if (
            !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 1
        ) {
            throw new com.voltcanary.vcl.VclException("JOB_REVISION_INVALID", "");
        }
        return imports.advance(rows.account(actor), actor.id(), id, revision.longValue());
    }

    public NativeImport cancel(AuthenticatedUser actor, UUID id, byte[] body) {
        NativeReviewRequests.reconciliation(body);
        return imports.cancel(rows.account(actor), actor.id(), id);
    }

    public com.fasterxml.jackson.databind.JsonNode chunks(
        AuthenticatedUser actor,
        UUID id,
        int afterChunk,
        int limit
    ) {
        return imports.chunks(rows.account(actor), actor.id(), id, afterChunk, limit);
    }
}
