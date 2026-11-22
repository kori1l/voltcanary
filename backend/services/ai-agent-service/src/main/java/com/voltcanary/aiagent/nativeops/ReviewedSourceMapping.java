package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope;
import java.util.Objects;
import java.util.UUID;

/** Immutable parameters shared by confirmed dispatch and receipt inspection. */
record ReviewedSourceMapping(
    UUID bindingId,
    long schemaRevision,
    UUID fieldId,
    JsonNode conversion,
    String digest
) {
    ReviewedSourceMapping {
        Objects.requireNonNull(bindingId);
        Objects.requireNonNull(fieldId);
        if (
            schemaRevision < 1 ||
            schemaRevision > 9_007_199_254_740_991L ||
            digest == null ||
            !digest.matches("[0-9a-f]{64}")
        ) {
            throw new IllegalArgumentException("NATIVE_MAPPING_REVIEW_CHANGED");
        }
        conversion = conversion.deepCopy();
        com.voltcanary.vcl.VclBooleanTokens.parse(conversion, "/conversion");
    }

    @Override
    public JsonNode conversion() {
        return conversion.deepCopy();
    }

    JsonNode confirm(NativeOwnerClient owner, String authorization, Scope scope) {
        return owner.confirmMapping(
            authorization,
            scope,
            bindingId,
            schemaRevision,
            fieldId,
            conversion(),
            digest
        );
    }

    JsonNode inspect(NativeOwnerClient owner, String authorization, Scope scope) {
        return owner.inspectMapping(
            authorization,
            scope,
            bindingId,
            schemaRevision,
            fieldId,
            digest
        );
    }
}
