package com.voltcanary.device.configurable.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed envelopes retain duplicate-key/UTF-8 checks before Spring can coerce a body into a DTO. */
public final class NativeReviewRequests {

    private static final VclParser PARSER = new VclParser();

    public record Reservation(List<String> fieldKeys, String idempotencyKey) {}

    public record Preparation(UUID reservationId, String document) {}

    public record Application(long reviewRevision, String reviewDigest, String idempotencyKey) {}

    public static Reservation reservation(byte[] body) {
        var json = closed(body, Set.of("kind", "fieldKeys", "idempotencyKey"));
        require(json.path("kind").asText().equals("COLLECTION"), "RESERVATION_KIND_UNSUPPORTED");
        var values = json.path("fieldKeys");
        require(
            values.isArray() && values.size() >= 1 && values.size() <= 64,
            "FIELD_KEYS_INVALID"
        );
        var keys = new ArrayList<String>();
        for (var value : values) {
            require(
                value.isTextual() && value.asText().matches("[A-Za-z][A-Za-z0-9_]{0,63}"),
                "FIELD_KEYS_INVALID"
            );
            keys.add(value.asText());
        }
        require(Set.copyOf(keys).size() == keys.size(), "FIELD_KEYS_INVALID");
        return new Reservation(List.copyOf(keys), key(json.path("idempotencyKey")));
    }

    public static Preparation preparation(byte[] body) {
        var json = closed(body, Set.of("reservationId", "document"));
        require(
            json.path("reservationId").isTextual() &&
                json
                    .path("reservationId")
                    .asText()
                    .matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    ),
            "RESERVATION_ID_INVALID"
        );
        UUID id;
        try {
            id = UUID.fromString(json.path("reservationId").asText());
        } catch (IllegalArgumentException invalid) {
            throw new VclException("RESERVATION_ID_INVALID", "");
        }
        require(json.path("document").isObject(), "DOCUMENT_REQUIRED");
        return new Preparation(id, json.path("document").toString());
    }

    public static Application application(byte[] body) {
        var json = closed(body, Set.of("reviewRevision", "reviewDigest", "idempotencyKey"));
        require(
            json.path("reviewRevision").isIntegralNumber() &&
                json.path("reviewRevision").canConvertToLong() &&
                json.path("reviewRevision").asLong() >= 1,
            "REVIEW_REVISION_INVALID"
        );
        require(
            json.path("reviewDigest").isTextual() &&
                json.path("reviewDigest").asText().matches("[0-9a-f]{64}"),
            "REVIEW_DIGEST_INVALID"
        );
        return new Application(
            json.path("reviewRevision").asLong(),
            json.path("reviewDigest").asText(),
            key(json.path("idempotencyKey"))
        );
    }

    public static void reconciliation(byte[] body) {
        if (body != null && body.length != 0) closed(body, Set.of());
    }

    public static String key(JsonNode value) {
        String key = value.asText();
        require(
            value.isTextual() &&
                !key.isBlank() &&
                key.codePointCount(0, key.length()) <= 160 &&
                key.indexOf('\0') < 0,
            "IDEMPOTENCY_KEY_INVALID"
        );
        return key;
    }

    public static JsonNode closed(byte[] body, Set<String> fields) {
        var json = PARSER.readJson(body);
        require(json.isObject() && json.size() == fields.size(), "REQUEST_FIELDS_INVALID");
        for (String field : fields) require(json.has(field), "REQUEST_FIELDS_INVALID");
        return json;
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }

    private NativeReviewRequests() {}
}
