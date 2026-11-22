package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.vcl.DurableSustained;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel;
import com.voltcanary.analytics.vcl.VclSustainedPlan;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.Metadata;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.SustainedPreview;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationPage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

final class RuleSustainedPreparation {
    private final RuleCompilationSnapshot snapshot;

    RuleSustainedPreparation(RuleCompilationSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    VclSustainedPlan prepareSustained(VclCompiler compiler, String nodeId) {
        return VclSustainedPlan.compile(
                snapshot.compile(compiler), nodeId, snapshot.new Metadata());
    }

    String sustainedEvaluatorPlanDigest(VclCompiler compiler, String nodeId, String bundleDigest) {
        var compilation = snapshot.compile(compiler);
        var methods =
                compilation.graph().stream()
                        .map(VclCompilation.Node::method)
                        .filter(method -> !"filter".equals(method))
                        .toList();
        if (methods.size() != 3
                || !java.util.Set.copyOf(methods)
                        .equals(java.util.Set.of("read_stream", "sustained", "emit_alert"))) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        snapshot.sessionAlertIntent(nodeId, bundleDigest)
                .orElseThrow(() -> new VclException("EVALUATOR_PROFILE_UNAVAILABLE", ""));
        var plan = VclSustainedPlan.compile(compilation, nodeId, snapshot.new Metadata());
        var journal =
                snapshot.journals.get(
                        new SourceBindingSnapshots.Target(plan.binding(), plan.schemaRevision()));
        if (journal == null || plan.timing().requiredInputs().size() != 1) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        snapshot.reauthorize();
        return VclCanonical.sha256(
                String.join(
                        "\n",
                        "DIRECT_JOURNAL_SUSTAINED_EVALUATOR/1",
                        DurableSustained.PROFILE,
                        compilation.semanticDigest(),
                        compilation.accessScopeDigest(),
                        plan.digest(),
                        plan.timing().digest(),
                        journal.cutDigest()));
    }

    void observeSustained(
            VclSustainedPlan plan,
            String nodeId,
            JdbcRuleDeployments.Deployment deployment,
            JdbcKeyedStateStore store,
            JsonNode event,
            UUID holder,
            Runnable deploymentCheck) {
        var start =
                snapshot.journals.get(
                        new SourceBindingSnapshots.Target(plan.binding(), plan.schemaRevision()));
        if (start == null || !start.accountId().equals(deployment.account())) {
            throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        }
        var at = Instant.parse(event.path("eventTime").textValue());
        if (!deployment.includesWindow(at)) return;
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var subject = RuleCompilationSnapshot.identity(event, plan.subjectField(), id);
        if (subject == null) return;
        var sample = sustainedSample(event, id, at);
        if (plan.excludes(sample)) return;
        var journal = start.atCheckpoint(event.path("checkpoint").textValue());
        var key = sustainedKey(journal, deployment.epoch(), nodeId, subject);
        var durable =
                new DurableSustained(
                        store,
                        plan,
                        key,
                        deployment.bundleDigest(),
                        () -> {
                            snapshot.reauthorize();
                            deploymentCheck.run();
                        },
                        (target, lease, transition, openedAt) ->
                                store.commitReviewedInput(
                                        target, lease, transition, openedAt, deployment));
        durable.observe(
                sample,
                VclCanonical.sha256(VclCanonical.encode(event)),
                snapshot.scope,
                holder,
                new DurableSustained.JournalPin(
                        journal.inputGeneration(), journal.capturedHeadCheckpoint()),
                (from, through) -> sustainedHistory(journal, plan, key, from, through));
    }

    List<VclSustainedPlan.Sample> sustainedHistory(
            SourceCaptureSnapshot journal,
            VclSustainedPlan plan,
            KeyedStateStore.Key key,
            Instant from,
            Instant through) {
        var rows = new ArrayList<VclSustainedPlan.Sample>();
        var until = through.plus(1, java.time.temporal.ChronoUnit.MICROS);
        long remaining = RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES;
        while (from.isBefore(until)) {
            var end = from.plus(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL);
            if (end.isAfter(until)) end = until;
            String after = journal.generationStartCheckpoint();
            VclObservationPage page;
            do {
                page = journal.readPage(after, 128, from, end);
                remaining -= page.bytes().length;
                if (remaining < 0) throw new VclException("WINDOW_INPUT_BYTE_BUDGET_EXCEEDED", "");
                for (var event : page.observations()) {
                    UUID id = UUID.fromString(event.path("inputEventId").textValue());
                    var subject = RuleCompilationSnapshot.identity(event, plan.subjectField(), id);
                    if (subject != null && !key.subjectDigest().equals(subject)) continue;
                    var sample =
                            sustainedSample(
                                    event, id, Instant.parse(event.path("eventTime").textValue()));
                    if (plan.excludes(sample)) continue;
                    if (subject == null) throw new VclException("WITNESS_SUBJECT_UNKNOWN", "");
                    if (rows.size() >= VclDurationWitnessKernel.MAX_INPUTS) {
                        throw new VclException("WITNESS_INPUT_LIMIT", "");
                    }
                    rows.add(sample);
                }
                after = page.nextCheckpoint();
            } while (page.hasMore());
            from = end;
        }
        return rows;
    }

    static VclSustainedPlan.Sample sustainedSample(JsonNode event, UUID id, Instant at) {
        return new VclSustainedPlan.Sample(
                id,
                at,
                Instant.parse(event.path("recordedAt").textValue()),
                RuleCompilationSnapshot.observationFields(event, id));
    }

    SustainedPreview previewDirectSustained(
            VclCompiler compiler,
            String nodeId,
            UUID previewEpoch,
            ScalarValue subject,
            VclWindowPlan.Window window,
            long byteBudget) {
        var plan = prepareSustained(compiler, nodeId);
        var journal =
                snapshot.journals.get(
                        new SourceBindingSnapshots.Target(plan.binding(), plan.schemaRevision()));
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        var key =
                sustainedKey(
                        journal, previewEpoch, nodeId, VclWindowSessionKernel.identity(subject));
        return previewSustained(plan, journal, key, window, byteBudget);
    }

    KeyedStateStore.Key sustainedKey(
            SourceCaptureSnapshot journal, UUID epoch, String nodeId, String subject) {
        return new KeyedStateStore.Key(
                journal.accountId(),
                UUID.fromString(snapshot.document.spec().path("ruleId").textValue()),
                epoch,
                subject,
                VclCanonical.sha256(
                        "VCL_SUSTAINED_CURSOR/1\n" + nodeId + "\n" + journal.inputGeneration()));
    }

    SustainedPreview previewSustained(
            VclSustainedPlan plan,
            SourceCaptureSnapshot journal,
            KeyedStateStore.Key key,
            VclWindowPlan.Window window,
            long byteBudget) {
        if (byteBudget <= 0 || byteBudget > RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES) {
            throw new VclException("WINDOW_INPUT_BYTE_BUDGET_INVALID", "");
        }
        if (journal == null || !journal.accountId().equals(key.accountId())) {
            throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        }
        var until = window.endExclusive().plus(1, java.time.temporal.ChronoUnit.MICROS);
        var rows = new ArrayList<VclSustainedPlan.Sample>();
        var ids = new HashSet<UUID>();
        String cut = journal.cutDigest();
        var fingerprints =
                new StringBuilder(
                        String.join(
                                "\n",
                                "RULE_SUSTAINED_JOURNAL/1",
                                cut,
                                plan.digest(),
                                key.digest(),
                                window.startInclusive().toString(),
                                window.endExclusive().toString()));
        UUID unresolved = null;
        for (var from = window.startInclusive(); from.isBefore(until); ) {
            var chunkEnd = from.plus(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL);
            if (chunkEnd.isAfter(until)) chunkEnd = until;
            String after = journal.generationStartCheckpoint();
            VclObservationPage page;
            do {
                page = journal.readPage(after, 128, from, chunkEnd);
                byteBudget -= page.bytes().length;
                if (byteBudget < 0) throw new VclException("WINDOW_INPUT_BYTE_BUDGET_EXCEEDED", "");
                for (var event : page.observations()) {
                    UUID id = UUID.fromString(event.path("inputEventId").textValue());
                    if (!ids.add(id)) throw new VclException("WINDOW_INPUT_EVENT_DUPLICATE", "");
                    var subject = RuleCompilationSnapshot.identity(event, plan.subjectField(), id);
                    if (subject != null && !key.subjectDigest().equals(subject)) continue;
                    var sample =
                            sustainedSample(
                                    event, id, Instant.parse(event.path("eventTime").textValue()));
                    if (plan.excludes(sample)) continue;
                    fingerprints
                            .append('\n')
                            .append(VclCanonical.sha256(VclCanonical.encode(event)));
                    if (subject == null) {
                        if (unresolved == null) unresolved = id;
                        continue;
                    }
                    if (rows.size() >= VclDurationWitnessKernel.MAX_INPUTS)
                        throw new VclException("WITNESS_INPUT_LIMIT", "");
                    rows.add(sample);
                }
                after = page.nextCheckpoint();
            } while (page.hasMore());
            from = chunkEnd;
        }
        var evaluation = plan.episodes(key, window, rows, snapshot.scope);
        if (unresolved != null && !evaluation.result().value().isFailure()) {
            var evidence = List.of(unresolved);
            evaluation =
                    new VclDurationWitnessKernel.Episodes(
                            new VclDurationWitnessKernel.Result(
                                    ScalarValue.unknown("WITNESS_SUBJECT_UNKNOWN", evidence),
                                    0,
                                    null,
                                    null,
                                    evidence),
                            List.of());
        }
        snapshot.reauthorize();
        return new SustainedPreview(
                cut,
                VclCanonical.sha256(fingerprints.toString()),
                evaluation.result(),
                evaluation.occurrences());
    }
}
