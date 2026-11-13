package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.vcl.VclParser;
import java.util.List;
import java.util.UUID;

/** Explicit outcome; unknown evidence never occupies a user literal or becomes false. */
public final class ScalarValue {
    public enum Status { OK, UNKNOWN, INVALID_INPUT, SCHEMA_UNAVAILABLE, ACCESS_REVOKED, STATE_LIMIT, OPERATOR_ERROR }

    private final Status status;
    private final JsonNode literal;
    private final String reason;
    private final List<UUID> evidence;

    private ScalarValue(Status status, JsonNode literal, String reason, List<UUID> evidence) {
        this.status = status;
        this.literal = literal == null ? null : literal.deepCopy();
        this.reason = reason;
        this.evidence = List.copyOf(evidence);
    }

    public static ScalarValue known(VclParser parser, String wireLiteral) {
        return new ScalarValue(Status.OK, parser.literal(wireLiteral), null, List.of());
    }

    static ScalarValue known(JsonNode literal) {
        return new ScalarValue(Status.OK, literal, null, List.of());
    }

    public static ScalarValue unknown(String reason, List<UUID> evidence) {
        if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{0,63}") || evidence.size() > 128) {
            throw new IllegalArgumentException("Invalid unknown diagnostic");
        }
        return new ScalarValue(Status.UNKNOWN, null, reason, evidence);
    }

    public static ScalarValue failure(Status status, String reason) {
        if (status == Status.OK || status == Status.UNKNOWN || reason == null || !reason.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid failure diagnostic");
        }
        return new ScalarValue(status, null, reason, List.of());
    }

    public Status status() { return status; }
    public JsonNode literal() { return literal == null ? null : literal.deepCopy(); }
    public String reason() { return reason; }
    public List<UUID> evidence() { return evidence; }
    public boolean isNull() { return status == Status.OK && literal.path("type").asText().equals("NULL"); }
    public boolean isFailure() { return status != Status.OK && status != Status.UNKNOWN; }

    @Override public String toString() { return "ScalarValue[status=" + status + ", reason=" + reason + "]"; }
}
