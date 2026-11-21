package com.voltcanary.device.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.port.NativeSharing;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NativeSharingWorkflows {
    private final NativeSharing sharing;
    private final NativeRowWorkflows rows;

    public JsonNode prepare(AuthenticatedUser actor, UUID dataset, byte[] body) {
        var request = NativeReviewRequests.closed(body, Set.of("idempotencyKey", "definition"));
        return sharing.prepare(rows.account(actor), actor.id(), dataset, NativeReviewRequests.key(request.path("idempotencyKey")), request.path("definition"));
    }

    public JsonNode inspect(AuthenticatedUser actor, UUID dataset, UUID review) {
        return sharing.inspect(rows.account(actor), actor.id(), dataset, review);
    }

    public JsonNode apply(AuthenticatedUser actor, UUID dataset, UUID review, byte[] body) {
        var request = NativeReviewRequests.application(body);
        return sharing.apply(rows.account(actor), actor.id(), dataset, review, request.reviewRevision(), request.reviewDigest(), request.idempotencyKey());
    }

    public JsonNode list(AuthenticatedUser actor, UUID dataset) {
        return sharing.list(rows.account(actor), actor.id(), dataset);
    }

    public JsonNode revoke(AuthenticatedUser actor, UUID dataset, UUID grant, byte[] body) {
        var request = NativeReviewRequests.closed(body, Set.of("expectedRevision", "idempotencyKey"));
        if (!request.path("expectedRevision").isIntegralNumber() || !request.path("expectedRevision").canConvertToLong()) {
            throw new VclException("REVIEW_REVISION_INVALID", "");
        }
        return sharing.revoke(rows.account(actor), actor.id(), dataset, grant, request.path("expectedRevision").longValue(),
                NativeReviewRequests.key(request.path("idempotencyKey")));
    }
}
