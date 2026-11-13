package com.voltcanary.alert.configurable.application;

import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.util.Set;
import java.util.UUID;

/** Closed transport contracts; actor, grant/rule IDs, policy and read pins are owner-generated. */
public final class AutomationGrantRequests {
    public record Reservation(UUID ruleReviewId, String key) { }
    public record Preparation(UUID reservationId, AutomationGrantScope scope) { }
    public record Application(long revision, String digest, String key) { }
    public record Revocation(long epoch, String key) { }
    private static final VclParser PARSER = new VclParser();
    private AutomationGrantRequests() { }

    public static Reservation reserve(byte[] body) {
        var value = PARSER.readJson(body);
        AutomationGrantScope.fields(value, Set.of("kind", "ruleReviewId", "idempotencyKey"));
        if (!value.path("kind").asText().equals("AUTOMATION_GRANT")) throw new VclException("REQUEST_FIELDS_INVALID", "");
        return new Reservation(AutomationGrantScope.uuid(value.path("ruleReviewId")), key(value.path("idempotencyKey")));
    }

    public static Preparation prepare(byte[] body) {
        var value = PARSER.readJson(body);
        AutomationGrantScope.fields(value, Set.of("reservationId", "definition"));
        return new Preparation(AutomationGrantScope.uuid(value.path("reservationId")), AutomationGrantScope.parse(value.path("definition")));
    }

    public static Application apply(byte[] body) {
        var value = PARSER.readJson(body);
        AutomationGrantScope.fields(value, Set.of("reviewRevision", "reviewDigest", "idempotencyKey"));
        if (!value.path("reviewRevision").isIntegralNumber() || !value.path("reviewRevision").canConvertToLong()
                || value.path("reviewRevision").asLong() < 1) throw new VclException("REVIEW_REVISION_INVALID", "");
        if (!value.path("reviewDigest").isTextual() || !value.path("reviewDigest").asText().matches("[0-9a-f]{64}")) {
            throw new VclException("REVIEW_DIGEST_INVALID", "");
        }
        return new Application(value.path("reviewRevision").asLong(), value.path("reviewDigest").asText(), key(value.path("idempotencyKey")));
    }

    public static Revocation revoke(byte[] body) {
        var value = PARSER.readJson(body);
        AutomationGrantScope.fields(value, Set.of("expectedEpoch", "idempotencyKey"));
        long epoch;
        try {
            if (!value.path("expectedEpoch").isTextual() || !value.path("expectedEpoch").asText().matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            epoch = Long.parseLong(value.path("expectedEpoch").asText());
        } catch (IllegalArgumentException failure) { throw new VclException("GRANT_EPOCH_INVALID", ""); }
        return new Revocation(epoch, key(value.path("idempotencyKey")));
    }

    public static void empty(byte[] body) {
        if (body == null || body.length == 0) return;
        AutomationGrantScope.fields(PARSER.readJson(body), Set.of());
    }

    private static String key(com.fasterxml.jackson.databind.JsonNode value) {
        String key = value.asText();
        if (!value.isTextual() || key.isBlank() || key.indexOf('\0') >= 0 || key.codePointCount(0, key.length()) > 160) {
            throw new VclException("IDEMPOTENCY_KEY_INVALID", "");
        }
        return key;
    }
}
