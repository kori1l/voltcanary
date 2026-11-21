package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclSourceCoverage;
import java.util.UUID;

final class SessionAlertProposal {
    static final String PROFILE = "VCL_SESSION_ALERT_PROPOSAL/1";
    static final String RETRACTION = "VCL_SESSION_ALERT_RETRACTION/1";
    static final String CORRECTION = "VCL_SESSION_ALERT_CORRECTION/1";
    static final String SUSTAINED = "VCL_SUSTAINED_ALERT_PROPOSAL/1";
    static final String SUSTAINED_RETRACTION = "VCL_SUSTAINED_ALERT_RETRACTION/1";
    static final String SUSTAINED_CORRECTION = "VCL_SUSTAINED_ALERT_CORRECTION/1";
    static final String INTERVAL = "VCL_INTERVAL_WINDOW_ALERT_PROPOSAL/1";
    static final String INTERVAL_PREFIX = "VCL_INTERVAL_PREFIX_ALERT_PROPOSAL/1";
    static final String INTERVAL_RETRACTION = "VCL_INTERVAL_WINDOW_ALERT_RETRACTION/1";
    static final String INTERVAL_CORRECTION = "VCL_INTERVAL_WINDOW_ALERT_CORRECTION/1";
    static final String COUNT = "VCL_COUNT_WINDOW_ALERT_PROPOSAL/1";
    static final String COUNT_PREFIX = "VCL_COUNT_PREFIX_ALERT_PROPOSAL/1";
    static final String COUNT_RETRACTION = "VCL_COUNT_WINDOW_ALERT_RETRACTION/1";
    static final String COUNT_CORRECTION = "VCL_COUNT_WINDOW_ALERT_CORRECTION/1";
    static final String AGGREGATE = "VCL_AGGREGATE_WINDOW_ALERT_PROPOSAL/1";
    static final String AGGREGATE_RETRACTION = "VCL_AGGREGATE_WINDOW_ALERT_RETRACTION/1";
    static final String AGGREGATE_CORRECTION = "VCL_AGGREGATE_WINDOW_ALERT_CORRECTION/1";

    static KeyedStateStore.Output create(KeyedStateStore.Key key, UUID id, KeyedStateStore.Output measure,
            VclWindowPlan.Window window, DurableJournalWindow.AlertIntent intent, UUID binding, long revision,
            VclSourceCoverage proof, String coverageDigest, String profile) {
        return create(key, key.occurrenceDigest(), id, measure, window, intent, binding, revision, proof, coverageDigest, profile);
    }

    static KeyedStateStore.Output create(KeyedStateStore.Key key, String occurrence, UUID id, KeyedStateStore.Output measure,
            VclWindowPlan.Window window, DurableJournalWindow.AlertIntent intent, UUID binding, long revision,
            VclSourceCoverage proof, String coverageDigest, String profile) {
        return create(key, occurrence, id, measure, window, intent, binding, revision, proof, coverageDigest, profile, null, java.util.List.of());
    }

    static KeyedStateStore.Output createSession(KeyedStateStore.Key key, UUID id, KeyedStateStore.Output measure,
            VclWindowPlan.Window window, DurableJournalWindow.AlertIntent intent, UUID binding, long revision,
            VclSourceCoverage proof, String coverageDigest, String profile, com.fasterxml.jackson.databind.JsonNode evidence, int limit) {
        return create(key, key.occurrenceDigest(), id, measure, window, intent, binding, revision, proof, coverageDigest,
                profile, null, observationReferences(evidence).stream().limit(limit).toList());
    }

    static java.util.List<String> observationReferences(com.fasterxml.jackson.databind.JsonNode measure) {
        var references = new java.util.LinkedHashSet<String>();
        for (String field : java.util.List.of("firstWitness", "lastWitness")) {
            var value = measure.path(field);
            if (value.isTextual()) references.add(value.textValue());
        }
        measure.path("positionWitnesses").forEach(value -> references.add(value.textValue()));
        for (String field : java.util.List.of("entryObservation", "exitObservation")) {
            var value = measure.path(field);
            if (value.isTextual()) references.add(value.textValue());
        }
        return java.util.List.copyOf(references);
    }

    static KeyedStateStore.Output createIntervalPrefix(KeyedStateStore.Key key, UUID id, KeyedStateStore.Output measure,
            VclWindowPlan.Window window, DurableJournalWindow.AlertIntent intent, UUID binding, long revision,
            VclSourceCoverage proof, String coverageDigest, java.time.Instant measuredThrough, boolean count) {
        return create(key, key.occurrenceDigest(), id, measure, window, intent, binding, revision, proof, coverageDigest,
                count ? COUNT_PREFIX : INTERVAL_PREFIX, measuredThrough, java.util.List.of());
    }

    private static KeyedStateStore.Output create(KeyedStateStore.Key key, String occurrence, UUID id, KeyedStateStore.Output measure,
            VclWindowPlan.Window window, DurableJournalWindow.AlertIntent intent, UUID binding, long revision,
            VclSourceCoverage proof, String coverageDigest, String profile, java.time.Instant measuredThrough,
            java.util.List<String> references) {
        String dedup = VclCanonical.sha256(String.join("\n", "RULE_SUBJECT_OCCURRENCE/1", key.ruleId().toString(),
                key.subjectDigest(), occurrence));
        var body = JsonNodeFactory.instance.objectNode().put("profile", profile)
                .put("account", key.accountId().toString()).put("rule", key.ruleId().toString())
                .put("epoch", key.deploymentEpoch().toString()).put("bundleDigest", intent.bundleDigest())
                .put("grant", intent.grant().toString()).put("grantEpoch", Long.toString(intent.grantEpoch()))
                .put("grantDigest", intent.grantDigest()).put("policy", intent.policy().toString())
                .put("policyDigest", intent.policyDigest()).put("severity", intent.severity())
                .put("dedup", dedup).put("subjectDigest", key.subjectDigest()).put("occurrenceDigest", occurrence)
                .put("windowStart", window.startInclusive().toString()).put("windowEnd", window.endExclusive().toString())
                .put("binding", binding.toString()).put("schemaRevision", Long.toString(revision))
                .put("snapshot", proof.snapshotId().toString()).put("generation", proof.inputGeneration().toString())
                .put("measure", measure.id().toString()).put("measureDigest", measure.payload().digest())
                .put("coverageProof", proof.proofId().toString()).put("coverageDigest", coverageDigest);
        if (measuredThrough != null) body.put("measuredThrough", measuredThrough.toString());
        if (!references.isEmpty()) {
            var array = body.putArray("observationReferences");
            references.forEach(array::add);
        }
        return new KeyedStateStore.Output(id, KeyedStateStore.OutputKind.ALERT_PROPOSAL, null,
                KeyedStateStore.State.parse(VclCanonical.encode(body)));
    }

    private SessionAlertProposal() { }
}
