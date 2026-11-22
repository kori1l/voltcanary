package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Versioned implemented profile, shared by source publication and compilation consumers. */
public final class VclSourceBindingProfile {

    public static final String VERSION = "legacy-source-schema-1";
    public static final String SAMPLE_HOLD = "sampleHold";
    public static final String SAMPLE_HOLD_VERSION = "bounded-sample-hold-1";
    public static final String INTERVAL_REPORT = "intervalReport";
    public static final String OCCURRENCE_ROUTING = "occurrenceRouting";

    private VclSourceBindingProfile() {}

    public static JsonNode capabilities() {
        var value = JsonNodeFactory.instance
            .objectNode()
            .put("profile", VERSION)
            .put("normalizedObservationsReady", false)
            .put("transitionCoverage", "UNKNOWN")
            .put("deletionCoverage", "UNKNOWN")
            .put("negativeEvidenceReady", false)
            .put("eventTimeProvenance", "UNQUALIFIED");
        value.putNull("maximumGapUs");
        value.putNull("inputGeneration");
        return value;
    }

    public static OptionalLong sampleHoldUs(JsonNode ownerDefinition) {
        if (!ownerDefinition.has(SAMPLE_HOLD)) return OptionalLong.empty();
        var contract = ownerDefinition.path(SAMPLE_HOLD);
        String duration = contract.path("maxHoldUs").textValue();
        if (
            !contract.isObject() ||
            contract.size() != 2 ||
            !SAMPLE_HOLD_VERSION.equals(contract.path("version").textValue()) ||
            duration == null ||
            !duration.matches("[1-9][0-9]{0,17}")
        ) {
            throw new IllegalArgumentException("Invalid bounded sample hold");
        }
        return OptionalLong.of(Long.parseLong(duration));
    }

    public record IntervalReport(String startKey, String endKey, long maxSpanUs) {}

    public record IntervalFields(UUID start, UUID end, long maxSpanUs) {}

    public record OccurrenceRouting(String subjectKey, String occurrenceKey) {}

    public record OccurrenceFields(UUID subject, UUID occurrence) {}

    public static Optional<OccurrenceRouting> occurrenceRouting(JsonNode configuration) {
        if (!configuration.has(OCCURRENCE_ROUTING)) return Optional.empty();
        var contract = configuration.path(OCCURRENCE_ROUTING);
        String subject = contract.path("subjectKey").textValue();
        String occurrence = contract.path("occurrenceKey").textValue();
        if (
            !contract.isObject() ||
            contract.size() != 3 ||
            !"direct-occurrence-1".equals(contract.path("version").textValue()) ||
            !sourceKey(subject) ||
            !sourceKey(occurrence) ||
            subject.equals(occurrence)
        ) {
            throw new IllegalArgumentException("Invalid direct occurrence routing");
        }
        return Optional.of(new OccurrenceRouting(subject, occurrence));
    }

    public static Optional<OccurrenceFields> occurrenceFields(JsonNode definition) {
        return occurrenceRouting(definition).map(contract ->
            new OccurrenceFields(
                retainedField(definition, contract.subjectKey(), "string", "TEXT"),
                retainedField(definition, contract.occurrenceKey(), "string", "TEXT")
            )
        );
    }

    public static Optional<IntervalReport> intervalReport(JsonNode configuration) {
        if (!configuration.has(INTERVAL_REPORT)) return Optional.empty();
        var contract = configuration.path(INTERVAL_REPORT);
        String start = contract.path("startKey").textValue();
        String end = contract.path("endKey").textValue();
        String duration = contract.path("maxSpanUs").textValue();
        if (
            !contract.isObject() ||
            contract.size() != 4 ||
            !"interval-end-event-time-1".equals(contract.path("version").textValue()) ||
            !sourceKey(start) ||
            !sourceKey(end) ||
            start.equals(end) ||
            duration == null ||
            !duration.matches("[1-9][0-9]{0,12}")
        ) {
            throw new IllegalArgumentException("Invalid interval report contract");
        }
        long span = Long.parseLong(duration);
        if (span > 32L * 86_400_000_000L) throw new IllegalArgumentException(
            "Invalid interval report duration"
        );
        return Optional.of(new IntervalReport(start, end, span));
    }

    public static Optional<IntervalFields> intervalFields(JsonNode definition) {
        return intervalReport(definition).map(contract ->
            new IntervalFields(
                retainedField(definition, contract.startKey(), "instant", "INSTANT"),
                retainedField(definition, contract.endKey(), "instant", "INSTANT"),
                contract.maxSpanUs()
            )
        );
    }

    private static boolean sourceKey(String key) {
        return key != null && key.matches("[A-Za-z][A-Za-z0-9_.]{0,63}");
    }

    private static UUID retainedField(
        JsonNode definition,
        String key,
        String sourceType,
        String type
    ) {
        for (var mapping : definition.path("fieldMappings")) {
            if (
                !key.equals(mapping.path("sourceKey").textValue()) ||
                !mapping.path("vclReady").asBoolean() ||
                !sourceType.equals(mapping.path("sourceType").textValue())
            ) continue;
            String id = mapping.path("fieldId").textValue();
            for (var field : definition.path("fields")) {
                if (
                    id != null &&
                    id.equals(field.path("id").textValue()) &&
                    type.equals(field.path("type").textValue())
                ) {
                    return UUID.fromString(id);
                }
            }
        }
        throw new IllegalArgumentException(
            "Contract key must reference a retained " + type + " field"
        );
    }
}
