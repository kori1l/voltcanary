package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Exact bounded accepted-journal read preconditions, never caller-provided source authority. */
public record VclObservationRead(VclObservationReadiness.Pin pin, UUID inputGeneration,
        String afterCheckpoint, String throughCheckpoint, int limit,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String eventFrom,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String eventUntil) {
    private static final VclParser PARSER = new VclParser();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public VclObservationRead(VclObservationReadiness.Pin pin, UUID inputGeneration,
            String afterCheckpoint, String throughCheckpoint, int limit) {
        this(pin, inputGeneration, afterCheckpoint, throughCheckpoint, limit, null, null);
    }

    public VclObservationRead {
        Objects.requireNonNull(pin);
        Objects.requireNonNull(inputGeneration);
        if (inputGeneration.equals(pin.bindingId()) || inputGeneration.equals(pin.snapshotId()) || limit < 1 || limit > 128
                || VclObservationReadiness.checkpoint(afterCheckpoint) > VclObservationReadiness.checkpoint(throughCheckpoint)) {
            throw new IllegalArgumentException("Invalid observation read");
        }
        if (eventFrom != null || eventUntil != null) {
            var from = java.time.Instant.parse(Objects.requireNonNull(eventFrom));
            var until = java.time.Instant.parse(Objects.requireNonNull(eventUntil));
            if (!from.isBefore(until) || from.getNano() % 1000 != 0 || until.getNano() % 1000 != 0
                    || !from.toString().equals(eventFrom) || !until.toString().equals(eventUntil)
                    || java.time.Duration.between(from, until).compareTo(VclCoverageSearch.MAX_INTERVAL) > 0) {
                throw new IllegalArgumentException("Invalid event-time interval");
            }
        }
    }

    public byte[] bytes() {
        var value = JsonNodeFactory.instance.objectNode().put("inputGeneration", inputGeneration.toString())
                .put("afterCheckpoint", afterCheckpoint).put("throughCheckpoint", throughCheckpoint).put("limit", limit);
        value.set("pin", JSON.valueToTree(pin));
        if (eventFrom != null) value.put("eventFrom", eventFrom).put("eventUntil", eventUntil);
        return VclCanonical.encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static VclObservationRead parse(byte[] bytes) {
        try {
            if (bytes == null || bytes.length > 8192) throw new IllegalArgumentException();
            var value = PARSER.readJson(bytes);
            var fields = new java.util.HashSet<String>();
            value.fieldNames().forEachRemaining(fields::add);
            var required = new java.util.HashSet<>(Set.of("pin", "inputGeneration", "afterCheckpoint", "throughCheckpoint", "limit"));
            boolean ranged = value.has("eventFrom") || value.has("eventUntil");
            if (ranged) required.addAll(Set.of("eventFrom", "eventUntil"));
            if (!value.isObject() || !fields.equals(required)
                    || ranged && (!value.path("eventFrom").isTextual() || !value.path("eventUntil").isTextual())
                    || !value.path("inputGeneration").isTextual() || !value.path("afterCheckpoint").isTextual()
                    || !value.path("throughCheckpoint").isTextual() || !value.path("limit").isIntegralNumber()
                    || !value.path("limit").canConvertToInt()) throw new IllegalArgumentException();
            UUID generation = UUID.fromString(value.path("inputGeneration").textValue());
            if (!generation.toString().equals(value.path("inputGeneration").textValue())) throw new IllegalArgumentException();
            var pin = VclObservationReadiness.parsePin(VclCanonical.encode(value.path("pin")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new VclObservationRead(pin, generation, value.path("afterCheckpoint").textValue(),
                    value.path("throughCheckpoint").textValue(), value.path("limit").intValue(),
                    value.path("eventFrom").textValue(), value.path("eventUntil").textValue());
        } catch (RuntimeException invalid) { throw new VclException("OBSERVATION_READ_INVALID", ""); }
    }

    @Override public String toString() { return "ObservationRead[]"; }
}
