package com.voltcanary.device.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.port.NativeSchemaMigrations;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.vcl.VclException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NativeMigrationWorkflows {

    private final NativeRowWorkflows rows;
    private final NativeSchemaMigrations migrations;

    public JsonNode reserve(AuthenticatedUser actor, UUID dataset, byte[] body) {
        var request = NativeReviewRequests.closed(body, Set.of("idempotencyKey", "fieldKeys"));
        var fields = request.path("fieldKeys");
        if (!fields.isArray() || fields.size() > 64) throw new VclException(
            "FIELD_KEYS_INVALID",
            ""
        );
        var keys = new ArrayList<String>();
        for (var field : fields) {
            if (
                !field.isTextual() || !field.asText().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
            ) throw new VclException("FIELD_KEYS_INVALID", "");
            keys.add(field.asText());
        }
        if (new HashSet<>(keys).size() != keys.size()) throw new VclException(
            "FIELD_KEYS_INVALID",
            ""
        );
        return migrations.reserve(
            rows.account(actor),
            actor.id(),
            dataset,
            NativeReviewRequests.key(request.path("idempotencyKey")),
            keys
        );
    }

    public JsonNode prepare(AuthenticatedUser actor, UUID dataset, UUID review, byte[] body) {
        var request = NativeReviewRequests.closed(body, Set.of("schema", "mappings", "dataMode"));
        return migrations.prepare(rows.account(actor), actor.id(), dataset, review, request);
    }

    public JsonNode inspect(AuthenticatedUser actor, UUID dataset, UUID review) {
        return migrations.inspect(rows.account(actor), actor.id(), dataset, review);
    }

    public JsonNode apply(AuthenticatedUser actor, UUID dataset, UUID review, byte[] body) {
        var request = NativeReviewRequests.application(body);
        return migrations.apply(
            rows.account(actor),
            actor.id(),
            dataset,
            review,
            request.reviewRevision(),
            request.reviewDigest(),
            request.idempotencyKey()
        );
    }

    public JsonNode archive(AuthenticatedUser actor, UUID dataset, byte[] body) {
        var request = NativeReviewRequests.closed(
            body,
            Set.of("reviewId", "reviewRevision", "reviewDigest", "idempotencyKey")
        );
        return migrations.archive(rows.account(actor), actor.id(), dataset, request);
    }
}
