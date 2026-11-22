package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Local journal state only. This closed profile cannot attest upstream completeness or evaluator readiness. */
public record VclObservationReadiness(
    Pin pin,
    String profile,
    String state,
    UUID inputGeneration,
    String generationStartCheckpoint,
    String headCheckpoint,
    Instant generationStartedAt,
    boolean journalReady,
    boolean negativeEvidenceReady,
    boolean ruleExecutionReady,
    String transitionCoverage,
    String deletionCoverage,
    String eventTimeProvenance,
    Long maximumGapUs
) {
    public record Pin(
        UUID bindingId,
        UUID accountId,
        UUID deviceId,
        String externalSourceId,
        long schemaRevision,
        UUID snapshotId,
        String definitionDigest
    ) {
        public Pin {
            java.util.Objects.requireNonNull(bindingId);
            java.util.Objects.requireNonNull(accountId);
            java.util.Objects.requireNonNull(deviceId);
            java.util.Objects.requireNonNull(snapshotId);
            if (
                externalSourceId == null ||
                externalSourceId.isBlank() ||
                externalSourceId.length() > 512 ||
                schemaRevision < 1 ||
                schemaRevision > 9_007_199_254_740_991L ||
                definitionDigest == null ||
                !definitionDigest.matches("[0-9a-f]{64}")
            ) throw new IllegalArgumentException();
        }
    }

    private static final VclParser PARSER = new VclParser();

    public VclObservationReadiness {
        java.util.Objects.requireNonNull(pin);
        if (
            !VclObservationProfile.VERSION.equals(profile) ||
            !Set.of(
                "DISABLED",
                "SCHEMA_PENDING",
                "AWAITING_INPUT",
                "INPUT_PENDING",
                "READY"
            ).contains(state) ||
            journalReady != "READY".equals(state) ||
            negativeEvidenceReady ||
            ruleExecutionReady ||
            maximumGapUs != null ||
            !"UNKNOWN".equals(transitionCoverage) ||
            !"UNKNOWN".equals(deletionCoverage) ||
            !"UNQUALIFIED".equals(eventTimeProvenance)
        ) throw new IllegalArgumentException();
        boolean absent =
            inputGeneration == null &&
            generationStartCheckpoint == null &&
            headCheckpoint == null &&
            generationStartedAt == null;
        if (absent) {
            if (journalReady) throw new IllegalArgumentException();
        } else {
            if (
                inputGeneration == null ||
                inputGeneration.equals(pin.snapshotId()) ||
                inputGeneration.equals(pin.bindingId()) ||
                generationStartedAt == null ||
                checkpoint(generationStartCheckpoint) > checkpoint(headCheckpoint) ||
                generationStartedAt.getNano() % 1000 != 0 ||
                generationStartedAt.atOffset(java.time.ZoneOffset.UTC).getYear() < 1 ||
                generationStartedAt.atOffset(java.time.ZoneOffset.UTC).getYear() > 9999 ||
                Set.of("DISABLED", "SCHEMA_PENDING").contains(state) ||
                (journalReady &&
                    checkpoint(generationStartCheckpoint) == checkpoint(headCheckpoint))
            ) throw new IllegalArgumentException();
        }
    }

    public static VclObservationReadiness absent(Pin pin, String state) {
        return new VclObservationReadiness(
            pin,
            VclObservationProfile.VERSION,
            state,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            "UNKNOWN",
            "UNKNOWN",
            "UNQUALIFIED",
            null
        );
    }

    public static Pin parsePin(byte[] bytes) {
        try {
            return pin(PARSER.readJson(bytes));
        } catch (RuntimeException failure) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }

    public static VclObservationReadiness parse(byte[] bytes) {
        try {
            var value = PARSER.readJson(bytes);
            fields(
                value,
                Set.of(
                    "pin",
                    "profile",
                    "state",
                    "inputGeneration",
                    "generationStartCheckpoint",
                    "headCheckpoint",
                    "generationStartedAt",
                    "journalReady",
                    "negativeEvidenceReady",
                    "ruleExecutionReady",
                    "transitionCoverage",
                    "deletionCoverage",
                    "eventTimeProvenance",
                    "maximumGapUs"
                )
            );
            if (
                !value.path("journalReady").isBoolean() ||
                !value.path("negativeEvidenceReady").isBoolean() ||
                !value.path("ruleExecutionReady").isBoolean() ||
                !value.path("maximumGapUs").isNull()
            ) throw new IllegalArgumentException();
            String started = nullable(value.path("generationStartedAt"));
            Instant at = started == null ? null : Instant.parse(started);
            if (at != null && !at.toString().equals(started)) throw new IllegalArgumentException();
            return new VclObservationReadiness(
                pin(value.path("pin")),
                value.path("profile").textValue(),
                value.path("state").textValue(),
                value.path("inputGeneration").isNull() ? null : uuid(value.path("inputGeneration")),
                nullable(value.path("generationStartCheckpoint")),
                nullable(value.path("headCheckpoint")),
                at,
                value.path("journalReady").booleanValue(),
                value.path("negativeEvidenceReady").booleanValue(),
                value.path("ruleExecutionReady").booleanValue(),
                value.path("transitionCoverage").textValue(),
                value.path("deletionCoverage").textValue(),
                value.path("eventTimeProvenance").textValue(),
                null
            );
        } catch (RuntimeException failure) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }

    private static Pin pin(JsonNode value) {
        fields(
            value,
            Set.of(
                "bindingId",
                "accountId",
                "deviceId",
                "externalSourceId",
                "schemaRevision",
                "snapshotId",
                "definitionDigest"
            )
        );
        if (
            !value.path("schemaRevision").isIntegralNumber() ||
            !value.path("schemaRevision").canConvertToLong()
        ) throw new IllegalArgumentException();
        return new Pin(
            uuid(value.path("bindingId")),
            uuid(value.path("accountId")),
            uuid(value.path("deviceId")),
            value.path("externalSourceId").textValue(),
            value.path("schemaRevision").longValue(),
            uuid(value.path("snapshotId")),
            value.path("definitionDigest").textValue()
        );
    }

    public static long checkpoint(String value) {
        if (
            value == null || !value.matches("0|[1-9][0-9]{0,18}")
        ) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }

    private static String nullable(JsonNode value) {
        if (value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }

    private static UUID uuid(JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException();
        UUID id = UUID.fromString(value.textValue());
        if (!id.toString().equals(value.textValue())) throw new IllegalArgumentException();
        return id;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(expected)) throw new IllegalArgumentException();
    }

    @Override
    public String toString() {
        return "VclObservationReadiness[bindingId=" + pin.bindingId() + ", state=" + state + "]";
    }
}
