package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.UUID;

/** Rebind the stored review to its exact document/plan, actor, scope and original expiry. */
final class NativeImportIntegrity {
    private NativeImportIntegrity() { }

    static String digest(UUID id, UUID account, UUID actor, String documentDigest, String scope, String planDigest, Instant expires,
            int totalRows, int validRows, String errors) {
        return VclCanonical.sha256("NATIVE_IMPORT/2:" + id + ":" + account + ":" + actor + ":" + documentDigest + ":" + scope + ":" + planDigest
                + ":" + expires + ":" + totalRows + ":" + validRows + ":" + VclCanonical.sha256(errors));
    }

    static void verify(UUID id, UUID account, UUID actor, JsonNode saved) {
        String expected = digest(id, account, actor, saved.path("document_digest").asText(), saved.path("scope_digest").asText(),
                saved.path("plan_digest").asText(), Instant.parse(saved.path("expires_at").asText()),
                saved.path("total_rows").intValue(), saved.path("valid_rows").intValue(), saved.path("errors_json").asText());
        if (!expected.equals(saved.path("review_digest").asText())
                || !VclCanonical.sha256(saved.path("canonical_document").asText()).equals(saved.path("document_digest").asText())
                || !VclCanonical.sha256(saved.path("canonical_plan").asText()).equals(saved.path("plan_digest").asText())) {
            throw new VclException("IMPORT_INTEGRITY", "");
        }
        if (!saved.path("status").asText().equals("REJECTED") && saved.path("valid_rows").intValue() != saved.path("total_rows").intValue()) {
            throw new VclException("IMPORT_INTEGRITY", "");
        }
    }
}
