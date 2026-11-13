package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A bounded exact captured prefix, not upstream completeness, an absence proof or evaluator readiness. */
public record VclObservationPage(String profile, VclObservationRead request, String nextCheckpoint, boolean hasMore,
        String truncationReason, List<JsonNode> observations, String upstreamCoverage, String eventTimeProvenance,
        boolean negativeEvidenceReady, boolean ruleExecutionReady) {
    public static final String PROFILE = "accepted-observation-page-1";
    public static final int MAX_BYTES = 262144;
    private static final VclParser PARSER = new VclParser();
    private static final VclUnits UNITS = VclUnits.standard();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public VclObservationPage {
        if (!PROFILE.equals(profile) || request == null || observations == null || observations.size() > request.limit()
                || !"UNKNOWN".equals(upstreamCoverage) || !"UNQUALIFIED".equals(eventTimeProvenance)
                || negativeEvidenceReady || ruleExecutionReady) invalid();
        observations = observations.stream().map(JsonNode::deepCopy).map(value -> (JsonNode) value).toList();
        long checkpoint = VclObservationReadiness.checkpoint(request.afterCheckpoint());
        long through = VclObservationReadiness.checkpoint(request.throughCheckpoint());
        boolean ranged = request.eventFrom() != null;
        Instant from = ranged ? Instant.parse(request.eventFrom()) : null;
        Instant until = ranged ? Instant.parse(request.eventUntil()) : null;
        var ids = new HashSet<UUID>();
        for (var value : observations) {
            validateObservation(value, request);
            long next = VclObservationReadiness.checkpoint(value.path("checkpoint").textValue());
            if (!ids.add(uuid(value.path("inputEventId"))) || next <= checkpoint || next > through
                    || !ranged && next != checkpoint + 1) invalid();
            if (ranged) {
                var at = Instant.parse(value.path("eventTime").textValue());
                if (at.isBefore(from) || !at.isBefore(until)) invalid();
            }
            checkpoint = next;
        }
        if (checkpoint != VclObservationReadiness.checkpoint(nextCheckpoint)
                || !ranged && hasMore != (checkpoint < through) || hasMore && checkpoint >= through
                || hasMore && observations.isEmpty() || !hasMore && truncationReason != null
                || hasMore && !Set.of("PAGE_ROWS", "PAGE_BYTES").contains(truncationReason)
                || hasMore && "PAGE_ROWS".equals(truncationReason) && observations.size() != request.limit()) invalid();
    }

    public static VclObservationPage of(VclObservationRead request, List<JsonNode> rows, String reason) {
        String next = rows.isEmpty() ? request.afterCheckpoint() : rows.getLast().path("checkpoint").textValue();
        boolean more = VclObservationReadiness.checkpoint(next) < VclObservationReadiness.checkpoint(request.throughCheckpoint());
        return new VclObservationPage(PROFILE, request, next, more, more ? reason : null, rows, "UNKNOWN", "UNQUALIFIED", false, false);
    }

    public static VclObservationPage ranged(VclObservationRead request, List<JsonNode> rows, boolean more, String reason) {
        if (request.eventFrom() == null) invalid();
        String next = rows.isEmpty() ? request.afterCheckpoint() : rows.getLast().path("checkpoint").textValue();
        return new VclObservationPage(PROFILE, request, next, more, more ? reason : null, rows, "UNKNOWN", "UNQUALIFIED", false, false);
    }

    @Override public List<JsonNode> observations() { return observations.stream().map(JsonNode::deepCopy).map(value -> (JsonNode) value).toList(); }
    @Override public String toString() { return "ObservationPage[rows=" + observations.size() + "]"; }
    /** Cross-check values against the separately authenticated source schema, not just self-described row types. */
    public void requireSchema(JsonNode definition) {
        var expected = new java.util.HashMap<String, String>();
        for (var field : definition.path("fields")) {
            if (expected.putIfAbsent(field.path("id").asText(), fieldIdentity(field)) != null) invalid();
        }
        for (var observation : observations) {
            var actual = new java.util.HashMap<String, String>();
            observation.path("fields").forEach(field -> actual.put(field.path("fieldId").asText(), fieldIdentity(field)));
            if (!expected.equals(actual)) invalid();
        }
    }

    private static String fieldIdentity(JsonNode field) {
        return switch (field.path("type").asText()) {
            case "DECIMAL" -> "DECIMAL:" + field.path("unit").textValue();
            case "REF" -> "REF:" + VclCanonical.encode(field.path("target"));
            default -> field.path("type").asText();
        };
    }
    public byte[] bytes() {
        byte[] bytes = VclCanonical.encode(JSON.valueToTree(this)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) invalid();
        return bytes;
    }

    public static VclObservationPage parse(byte[] bytes) {
        try {
            if (bytes == null || bytes.length > MAX_BYTES) invalid();
            var value = PARSER.readJson(bytes);
            fields(value, Set.of("profile", "request", "nextCheckpoint", "hasMore", "truncationReason", "observations",
                    "upstreamCoverage", "eventTimeProvenance", "negativeEvidenceReady", "ruleExecutionReady"));
            if (!value.path("hasMore").isBoolean() || !value.path("negativeEvidenceReady").isBoolean() || !value.path("ruleExecutionReady").isBoolean()
                    || !value.path("nextCheckpoint").isTextual() || !value.path("observations").isArray()
                    || !value.path("truncationReason").isNull() && !value.path("truncationReason").isTextual()) invalid();
            var rows = new ArrayList<JsonNode>();
            value.path("observations").forEach(rows::add);
            return new VclObservationPage(value.path("profile").textValue(), VclObservationRead.parse(VclCanonical.encode(value.path("request")).getBytes(StandardCharsets.UTF_8)),
                    value.path("nextCheckpoint").textValue(), value.path("hasMore").booleanValue(), value.path("truncationReason").textValue(), rows,
                    value.path("upstreamCoverage").textValue(), value.path("eventTimeProvenance").textValue(),
                    value.path("negativeEvidenceReady").booleanValue(), value.path("ruleExecutionReady").booleanValue());
        } catch (RuntimeException failure) { throw new VclException("OWNER_SNAPSHOT_INVALID", ""); }
    }

    private static void validateObservation(JsonNode value, VclObservationRead request) {
        fields(value, Set.of("profile", "inputEventId", "inputGeneration", "eventTime", "receivedAt", "recordedAt", "checkpoint",
                "upstreamCoverage", "eventTimeProvenance", "negativeEvidenceReady", "ruleExecutionReady", "bindingSchemaPin", "fields"));
        if (!VclObservationProfile.VERSION.equals(value.path("profile").textValue())
                || !request.inputGeneration().equals(uuid(value.path("inputGeneration")))
                || !request.pin().equals(VclObservationReadiness.parsePin(VclCanonical.encode(value.path("bindingSchemaPin")).getBytes(StandardCharsets.UTF_8)))
                || !value.path("checkpoint").isTextual() || !"UNKNOWN".equals(value.path("upstreamCoverage").textValue())
                || !"UNQUALIFIED".equals(value.path("eventTimeProvenance").textValue())
                || !value.path("negativeEvidenceReady").isBoolean() || value.path("negativeEvidenceReady").booleanValue()
                || !value.path("ruleExecutionReady").isBoolean() || value.path("ruleExecutionReady").booleanValue()
                || !value.path("fields").isArray() || value.path("fields").size() > 32) invalid();
        instant(value.path("eventTime"), true);
        instant(value.path("recordedAt"), true);
        instant(value.path("receivedAt"), false);
        var ids = new HashSet<UUID>();
        for (var field : value.path("fields")) {
            String type = field.path("type").asText();
            boolean decimal = "DECIMAL".equals(type);
            fields(field, switch (type) {
                case "DECIMAL" -> Set.of("fieldId", "type", "knowledge", "reason", "value", "unit");
                case "REF" -> Set.of("fieldId", "type", "knowledge", "reason", "value", "target");
                default -> Set.of("fieldId", "type", "knowledge", "reason", "value");
            });
            if (!ids.add(uuid(field.path("fieldId"))) || !Set.of("BOOLEAN", "TEXT", "DECIMAL", "POINT", "REF", "INSTANT").contains(type)) invalid();
            if (decimal) UNITS.require(field.path("unit").textValue(), "");
            if ("REF".equals(type)) {
                var target = field.path("target");
                fields(target, Set.of("kind", "resourceKind"));
                if (!"RESOURCE".equals(target.path("kind").textValue())
                        || !"SPATIAL_FEATURE".equals(target.path("resourceKind").textValue())) invalid();
            }
            if ("UNKNOWN".equals(field.path("knowledge").textValue())) {
                if (!field.path("value").isNull() || !"MISSING_OR_NULL_INPUT".equals(field.path("reason").textValue())) invalid();
            } else {
                if (!"KNOWN".equals(field.path("knowledge").textValue()) || !field.path("reason").isNull()) invalid();
                if ("BOOLEAN".equals(field.path("type").textValue())) {
                    if (!field.path("value").isBoolean()) invalid();
                } else if (decimal) {
                    String text = field.path("value").textValue();
                    if (!com.voltcanary.common.events.telemetry.ConfiguredFields.canonicalDecimal(text).equals(text)) invalid();
                } else if ("POINT".equals(type)) {
                    var point = field.path("value");
                    fields(point, Set.of("longitude", "latitude"));
                    var longitude = point.path("longitude");
                    var latitude = point.path("latitude");
                    if (!longitude.isNumber() || !latitude.isNumber()
                            || longitude.decimalValue().abs().compareTo(java.math.BigDecimal.valueOf(180)) > 0
                            || latitude.decimalValue().abs().compareTo(java.math.BigDecimal.valueOf(90)) > 0) invalid();
                } else if ("REF".equals(type)) {
                    uuid(field.path("value"));
                } else if ("INSTANT".equals(type)) {
                    com.voltcanary.common.events.telemetry.ConfiguredFields.canonicalInstant(field.path("value").textValue());
                } else {
                    String text = field.path("value").textValue();
                    if (text == null || text.codePointCount(0, text.length()) > 512 || !StandardCharsets.UTF_8.newEncoder().canEncode(text)) invalid();
                }
            }
        }
    }

    private static void instant(JsonNode value, boolean microseconds) {
        if (!value.isTextual()) invalid();
        Instant instant = Instant.parse(value.textValue());
        int year = instant.atOffset(java.time.ZoneOffset.UTC).getYear();
        if (!instant.toString().equals(value.textValue()) || year < 1 || year > 9999 || microseconds && instant.getNano() % 1000 != 0) invalid();
    }
    private static UUID uuid(JsonNode value) {
        if (!value.isTextual()) invalid();
        UUID id = UUID.fromString(value.textValue());
        if (!id.toString().equals(value.textValue())) invalid();
        return id;
    }
    private static void fields(JsonNode value, Set<String> expected) {
        var actual = new HashSet<String>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!value.isObject() || !actual.equals(expected)) invalid();
    }
    private static void invalid() { throw new VclException("OWNER_SNAPSHOT_INVALID", ""); }
}
