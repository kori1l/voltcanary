package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

record WindowAlertProposal(
    JsonNode payload,
    String digest,
    UUID binding,
    long schemaRevision,
    Instant start,
    Instant end,
    AlertSeverity severity
) {
    private static final VclParser PARSER = new VclParser();
    private static final String WINDOW_PROOF_PIN = "VCL_WINDOW_ALERT_PROPOSAL/2";
    private static final String SESSION = "VCL_SESSION_ALERT_PROPOSAL/1";
    private static final String RETRACTION = "VCL_SESSION_ALERT_RETRACTION/1";
    private static final String CORRECTION = "VCL_SESSION_ALERT_CORRECTION/1";
    private static final String SUSTAINED = "VCL_SUSTAINED_ALERT_PROPOSAL/1";
    private static final String SUSTAINED_RETRACTION = "VCL_SUSTAINED_ALERT_RETRACTION/1";
    private static final String SUSTAINED_CORRECTION = "VCL_SUSTAINED_ALERT_CORRECTION/1";
    private static final String INTERVAL = "VCL_INTERVAL_WINDOW_ALERT_PROPOSAL/1";
    private static final String INTERVAL_PREFIX = "VCL_INTERVAL_PREFIX_ALERT_PROPOSAL/1";
    private static final String INTERVAL_RETRACTION = "VCL_INTERVAL_WINDOW_ALERT_RETRACTION/1";
    private static final String INTERVAL_CORRECTION = "VCL_INTERVAL_WINDOW_ALERT_CORRECTION/1";
    private static final String COUNT = "VCL_COUNT_WINDOW_ALERT_PROPOSAL/1";
    private static final String COUNT_PREFIX = "VCL_COUNT_PREFIX_ALERT_PROPOSAL/1";
    private static final String COUNT_RETRACTION = "VCL_COUNT_WINDOW_ALERT_RETRACTION/1";
    private static final String COUNT_CORRECTION = "VCL_COUNT_WINDOW_ALERT_CORRECTION/1";
    private static final String AGGREGATE = "VCL_AGGREGATE_WINDOW_ALERT_PROPOSAL/1";
    private static final String AGGREGATE_RETRACTION = "VCL_AGGREGATE_WINDOW_ALERT_RETRACTION/1";
    private static final String AGGREGATE_CORRECTION = "VCL_AGGREGATE_WINDOW_ALERT_CORRECTION/1";

    static WindowAlertProposal parse(byte[] bytes) {
        try {
            if (bytes.length > 8192) throw new IllegalArgumentException();
            var payload = PARSER.readJson(bytes);
            String canonical = VclCanonical.encode(payload);
            String profile = text(payload, "profile");
            boolean prefix = INTERVAL_PREFIX.equals(profile) || COUNT_PREFIX.equals(profile);
            boolean session =
                SESSION.equals(profile) ||
                RETRACTION.equals(profile) ||
                CORRECTION.equals(profile) ||
                SUSTAINED.equals(profile) ||
                SUSTAINED_RETRACTION.equals(profile) ||
                SUSTAINED_CORRECTION.equals(profile);
            boolean proofPin = WINDOW_PROOF_PIN.equals(profile);
            boolean hasReferences = payload.has("observationReferences");
            if (
                !payload.isObject() ||
                payload.size() != (prefix || proofPin || hasReferences ? 25 : 24) ||
                (!session &&
                    !prefix &&
                    !proofPin &&
                    !INTERVAL.equals(profile) &&
                    !INTERVAL_RETRACTION.equals(profile) &&
                    !INTERVAL_CORRECTION.equals(profile) &&
                    !COUNT.equals(profile) &&
                    !COUNT_RETRACTION.equals(profile) &&
                    !COUNT_CORRECTION.equals(profile) &&
                    !AGGREGATE.equals(profile) &&
                    !AGGREGATE_RETRACTION.equals(profile) &&
                    !AGGREGATE_CORRECTION.equals(profile) &&
                    !"VCL_WINDOW_ALERT_PROPOSAL/1".equals(profile)) ||
                !canonical.equals(new String(bytes, StandardCharsets.UTF_8))
            ) throw new IllegalArgumentException();
            if (hasReferences) {
                if (
                    !SESSION.equals(profile) &&
                    !RETRACTION.equals(profile) &&
                    !CORRECTION.equals(profile)
                ) {
                    throw new IllegalArgumentException();
                }
                var references = payload.path("observationReferences");
                if (
                    !references.isArray() || references.isEmpty() || references.size() > 100
                ) throw new IllegalArgumentException();
                var ids = new java.util.HashSet<UUID>();
                for (var reference : references) {
                    String text = reference.textValue();
                    UUID id = UUID.fromString(text);
                    if (
                        !id.toString().equals(text) || !ids.add(id)
                    ) throw new IllegalArgumentException();
                }
            }
            Instant start = Instant.parse(text(payload, "windowStart"));
            Instant end = Instant.parse(text(payload, "windowEnd"));
            if (
                !start.isBefore(end) ||
                start.getNano() % 1000 != 0 ||
                end.getNano() % 1000 != 0 ||
                !start.toString().equals(text(payload, "windowStart")) ||
                !end.toString().equals(text(payload, "windowEnd"))
            ) {
                throw new IllegalArgumentException();
            }
            if (prefix) {
                String through = text(payload, "measuredThrough");
                var instant = Instant.parse(through);
                if (
                    !instant.toString().equals(through) ||
                    instant.getNano() % 1000 != 0 ||
                    !instant.isAfter(start) ||
                    !instant.isBefore(end)
                ) throw new IllegalArgumentException();
            }
            for (var field : new String[] {
                "account",
                "rule",
                "epoch",
                "grant",
                "policy",
                "snapshot",
                "generation",
                "measure",
                "coverageProof",
            }) {
                uuid(payload, field);
            }
            for (var field : new String[] {
                "bundleDigest",
                "grantDigest",
                "policyDigest",
                "measureDigest",
                "coverageDigest",
                "subjectDigest",
                "occurrenceDigest",
                "dedup",
            }) {
                if (
                    !text(payload, field).matches("[0-9a-f]{64}")
                ) throw new IllegalArgumentException();
            }
            if (
                proofPin && !text(payload, "coverageProofDigest").matches("[0-9a-f]{64}")
            ) throw new IllegalArgumentException();
            positive(payload, "grantEpoch");
            String occurrence = session
                ? text(payload, "occurrenceDigest")
                : VclCanonical.sha256(
                      String.join(
                          "\n",
                          "RULE_WINDOW_OCCURRENCE/1",
                          start.toString(),
                          end.toString()
                      )
                  );
            String dedup = VclCanonical.sha256(
                String.join(
                    "\n",
                    "RULE_SUBJECT_OCCURRENCE/1",
                    text(payload, "rule"),
                    text(payload, "subjectDigest"),
                    occurrence
                )
            );
            if (
                !occurrence.equals(text(payload, "occurrenceDigest")) ||
                !dedup.equals(text(payload, "dedup"))
            ) {
                throw new IllegalArgumentException();
            }
            return new WindowAlertProposal(
                payload,
                VclCanonical.sha256(canonical),
                uuid(payload, "binding"),
                positive(payload, "schemaRevision"),
                start,
                end,
                AlertSeverity.valueOf(text(payload, "severity"))
            );
        } catch (RuntimeException invalid) {
            throw new VclException("ALERT_PROPOSAL_INVALID", "");
        }
    }

    String text(String field) {
        return text(payload, field);
    }

    UUID uuid(String field) {
        return uuid(payload, field);
    }

    long grantEpoch() {
        return positive(payload, "grantEpoch");
    }

    JsonNode observationReferences() {
        return payload.has("observationReferences")
            ? payload.path("observationReferences")
            : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    String expectedProofDigest() {
        if (WINDOW_PROOF_PIN.equals(text("profile"))) return text("coverageProofDigest");
        if (
            SESSION.equals(text("profile")) ||
            retraction() ||
            correction() ||
            SUSTAINED.equals(text("profile")) ||
            interval() ||
            count() ||
            aggregate()
        ) return text("coverageDigest");
        return null;
    }

    boolean retraction() {
        return (
            RETRACTION.equals(text("profile")) ||
            SUSTAINED_RETRACTION.equals(text("profile")) ||
            INTERVAL_RETRACTION.equals(text("profile")) ||
            COUNT_RETRACTION.equals(text("profile")) ||
            AGGREGATE_RETRACTION.equals(text("profile"))
        );
    }

    boolean correction() {
        return (
            CORRECTION.equals(text("profile")) ||
            SUSTAINED_CORRECTION.equals(text("profile")) ||
            INTERVAL_CORRECTION.equals(text("profile")) ||
            COUNT_CORRECTION.equals(text("profile")) ||
            AGGREGATE_CORRECTION.equals(text("profile"))
        );
    }

    boolean interval() {
        return (
            INTERVAL.equals(text("profile")) ||
            INTERVAL_RETRACTION.equals(text("profile")) ||
            INTERVAL_CORRECTION.equals(text("profile")) ||
            intervalPrefix()
        );
    }

    boolean intervalPrefix() {
        return INTERVAL_PREFIX.equals(text("profile"));
    }

    boolean count() {
        return java.util.Set.of(COUNT, COUNT_PREFIX, COUNT_RETRACTION, COUNT_CORRECTION).contains(
            text("profile")
        );
    }

    boolean prefix() {
        return intervalPrefix() || COUNT_PREFIX.equals(text("profile"));
    }

    boolean aggregate() {
        return java.util.Set.of(AGGREGATE, AGGREGATE_RETRACTION, AGGREGATE_CORRECTION).contains(
            text("profile")
        );
    }

    Instant effectEnd() {
        return prefix() ? Instant.parse(text("measuredThrough")) : end;
    }

    boolean sustained() {
        return (
            SUSTAINED.equals(text("profile")) ||
            SUSTAINED_RETRACTION.equals(text("profile")) ||
            SUSTAINED_CORRECTION.equals(text("profile"))
        );
    }

    private static String text(JsonNode value, String field) {
        var text = value.path(field).textValue();
        if (text == null) throw new IllegalArgumentException();
        return text;
    }

    private static UUID uuid(JsonNode value, String field) {
        var text = text(value, field);
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException();
        return id;
    }

    private static long positive(JsonNode value, String field) {
        var text = text(value, field);
        if (!text.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
        return Long.parseLong(text);
    }
}
