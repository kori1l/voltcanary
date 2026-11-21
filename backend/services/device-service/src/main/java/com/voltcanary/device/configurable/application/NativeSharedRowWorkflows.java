package com.voltcanary.device.configurable.application;

import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeSharedRowChanges;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NativeSharedRowWorkflows {
    private static final VclParser PARSER = new VclParser();
    private final NativeSharedRowChanges changes;

    public NativeRowWorkflows.View reserve(NativeSharedAccess access, byte[] body) {
        var request = NativeReviewRequests.closed(body, Set.of("schemaRevision", "idempotencyKey"));
        var revision = request.path("schemaRevision");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 1) throw new VclException("TARGET_INVALID", "");
        return NativeRowWorkflows.view(changes.reserve(access, revision.longValue(), NativeReviewRequests.key(request.path("idempotencyKey"))));
    }

    public NativeRowWorkflows.View prepare(NativeSharedAccess access, byte[] body) {
        var request = NativeReviewRequests.preparation(body);
        var document = PARSER.parse(request.document());
        if (!document.spec().at("/target/datasetId").asText().equals(access.dataset().toString())) throw new VclException("TARGET_CONFLICT", "");
        return NativeRowWorkflows.view(changes.prepare(access, request.reservationId(), document));
    }

    public NativeRowWorkflows.View inspect(NativeSharedAccess access, UUID review) {
        return NativeRowWorkflows.view(changes.inspect(access, review));
    }

    public NativeRowWorkflows.View apply(NativeSharedAccess access, UUID review, byte[] body) {
        var request = NativeReviewRequests.application(body);
        return NativeRowWorkflows.view(changes.apply(access, review, request.reviewRevision(), request.reviewDigest(), request.idempotencyKey()));
    }
}
