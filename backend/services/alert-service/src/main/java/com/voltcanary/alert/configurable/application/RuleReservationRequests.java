package com.voltcanary.alert.configurable.application;

import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The fixed RULE union member of the shared control reservation contract. */
public final class RuleReservationRequests {
    public record Reservation(List<String> outputKeys, String idempotencyKey) {
        public Reservation { outputKeys = List.copyOf(outputKeys); }
    }
    private static final VclParser PARSER = new VclParser();
    private RuleReservationRequests() { }

    public static Reservation parse(byte[] bytes) {
        var json = PARSER.readJson(bytes);
        var fields = new java.util.HashSet<String>();
        json.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("kind", "outputKeys", "idempotencyKey")) || !json.path("kind").asText().equals("RULE")) {
            throw new VclException("REQUEST_FIELDS_INVALID", "");
        }
        if (!json.path("outputKeys").isArray() || json.path("outputKeys").size() > 64) throw new VclException("OUTPUT_KEYS_INVALID", "/outputKeys");
        var keys = new ArrayList<String>();
        for (var value : json.path("outputKeys")) {
            if (!value.isTextual() || !value.asText().matches("[A-Za-z][A-Za-z0-9_]{0,63}") || keys.contains(value.asText())) {
                throw new VclException("OUTPUT_KEYS_INVALID", "/outputKeys");
            }
            keys.add(value.asText());
        }
        var value = json.path("idempotencyKey");
        String key = value.asText();
        if (!value.isTextual() || key.isBlank() || key.indexOf('\0') >= 0 || key.codePointCount(0, key.length()) > 160) {
            throw new VclException("IDEMPOTENCY_KEY_INVALID", "/idempotencyKey");
        }
        return new Reservation(keys.stream().sorted().toList(), key);
    }
}
