package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Bounded lookup for an event-time-qualified statement covering an exact interval. */
public record VclCoverageSearch(VclObservationReadiness.Pin pin, UUID generation, Instant from, Instant until) {
    private static final VclParser PARSER = new VclParser();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Duration MAX_INTERVAL = Duration.ofDays(34);

    public VclCoverageSearch {
        if (pin == null || generation == null || from == null || until == null || !from.isBefore(until)
                || from.getNano() % 1000 != 0 || until.getNano() % 1000 != 0
                || Duration.between(from, until).compareTo(MAX_INTERVAL) > 0) {
            throw new VclException("SOURCE_COVERAGE_SEARCH_INVALID", "");
        }
    }

    public byte[] bytes() {
        var body = MAPPER.createObjectNode().put("generation", generation.toString())
                .put("from", from.toString()).put("until", until.toString());
        body.set("pin", MAPPER.valueToTree(pin));
        return VclCanonical.encode(body).getBytes(StandardCharsets.UTF_8);
    }

    public static VclCoverageSearch parse(byte[] body) {
        try {
            if (body.length > 8192) throw new IllegalArgumentException();
            var value = PARSER.readJson(body);
            if (!value.isObject() || value.size() != 4) throw new IllegalArgumentException();
            var request = new VclCoverageSearch(VclObservationReadiness.parsePin(
                    VclCanonical.encode(value.path("pin")).getBytes(StandardCharsets.UTF_8)),
                    UUID.fromString(value.path("generation").textValue()), Instant.parse(value.path("from").textValue()),
                    Instant.parse(value.path("until").textValue()));
            if (!java.util.Arrays.equals(body, request.bytes())) throw new IllegalArgumentException();
            return request;
        } catch (RuntimeException invalid) { throw new VclException("SOURCE_COVERAGE_SEARCH_INVALID", ""); }
    }

    public static UUID parseResult(byte[] body) {
        try {
            var value = PARSER.readJson(body);
            if (!value.isObject() || value.size() != 1 || !value.has("proofId")) throw new IllegalArgumentException();
            var id = value.path("proofId");
            if (id.isNull()) return null;
            UUID result = UUID.fromString(id.textValue());
            if (!result.toString().equals(id.textValue())) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) { throw new VclException("OWNER_SNAPSHOT_INVALID", ""); }
    }
}
