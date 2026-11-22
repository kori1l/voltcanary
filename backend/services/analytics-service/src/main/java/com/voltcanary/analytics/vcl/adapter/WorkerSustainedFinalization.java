package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.DurableSustained;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.adapter.WorkerRuleCompilationSnapshots.SustainedFinalization;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

final class WorkerSustainedFinalization {
    private final WorkerRuleCompilationSnapshots ownerContext;

    WorkerSustainedFinalization(WorkerRuleCompilationSnapshots ownerContext) {
        this.ownerContext = ownerContext;
    }

    void finishSustained(
            JdbcRuleDeployments.Deployment candidate,
            JdbcKeyedStateStore store,
            JdbcKeyedStateStore.PendingSustained pending,
            VclCompiler compiler,
            UUID holder) {
        var key = pending.key();
        var source = store.inspectOutput(key, pending.pins(), pending.measure());
        if (source.receiptId() != null) return;
        var finalization = finalizeSustained(candidate, store, key, source.output(), compiler);
        if (finalization.isEmpty()) return;
        var result = finalization.get();
        var original =
                new VclParser()
                        .readJson(
                                source.output()
                                        .payload()
                                        .payload()
                                        .getBytes(StandardCharsets.UTF_8));
        String occurrence = original.path("occurrence").textValue();
        boolean idle = result.proof() == null;
        String coverageDigest = idle ? null : result.proof().eventTimeDigest();
        var measure =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("profile", "vcl-sustained-final-measure-1")
                        .put("sourceMeasure", source.output().id().toString())
                        .put("occurrence", occurrence)
                        .put("sessionStart", result.window().startInclusive().toString())
                        .put("sessionEnd", result.window().endExclusive().toString())
                        .put("status", result.confirmed() ? "OK" : "UNKNOWN");
        if (!idle) {
            measure.put("inputDigest", result.inputDigest())
                    .put("coverageDigest", coverageDigest)
                    .put("proof", result.proof().proofId().toString());
        }
        if (result.confirmed())
            measure.putObject("literal").put("type", "BOOLEAN").put("value", !result.retraction());
        else
            measure.putNull("literal")
                    .put("reason", idle ? "SUSTAINED_SOURCE_IDLE" : "SUSTAINED_WITNESS_CHANGED");
        if (original.path("correction").isTextual())
            measure.set("correction", original.path("correction"));
        UUID measureId =
                SessionAbsenceSchedule.id(pending.pins(), "sustained-final:" + pending.measure());
        var output =
                new KeyedStateStore.Output(
                        measureId,
                        KeyedStateStore.OutputKind.MEASURE,
                        null,
                        KeyedStateStore.State.parse(VclCanonical.encode(measure)));
        var outputs = new java.util.ArrayList<KeyedStateStore.Output>();
        outputs.add(output);
        if (result.confirmed() && result.intent() != null) {
            String profile =
                    result.retraction()
                            ? SessionAlertProposal.SUSTAINED_RETRACTION
                            : original.path("correction").isTextual()
                                    ? SessionAlertProposal.SUSTAINED_CORRECTION
                                    : SessionAlertProposal.SUSTAINED;
            outputs.add(
                    SessionAlertProposal.create(
                            key,
                            occurrence,
                            SessionAbsenceSchedule.id(
                                    pending.pins(), "sustained-alert:" + pending.measure()),
                            output,
                            result.window(),
                            result.intent(),
                            result.binding(),
                            result.revision(),
                            result.proof(),
                            coverageDigest,
                            profile));
        }
        var active = ownerContext.current(candidate);
        var lease = store.acquire(key, pending.pins(), holder);
        if (!idle)
            WorkerRuleCompilationSnapshots.recheckCoverage(
                    result.journal(), result.proof(), "SUSTAINED_COVERAGE_CHANGED");
        var receipt =
                new KeyedStateStore.Acknowledgement(
                        pending.measure(), source.output().digest(), measureId, output.digest());
        store.commitSustainedFinalization(
                key,
                lease,
                new KeyedStateStore.Transition(
                        measureId,
                        1,
                        source.output().digest(),
                        lease.snapshot().revision(),
                        lease.snapshot().state(),
                        lease.timers().stream().map(KeyedStateStore.ScheduledTimer::timer).toList(),
                        null,
                        outputs),
                result.window().startInclusive(),
                active,
                receipt);
    }

    java.util.Optional<SustainedFinalization> finalizeSustained(
            JdbcRuleDeployments.Deployment candidate,
            JdbcKeyedStateStore store,
            KeyedStateStore.Key key,
            KeyedStateStore.Output measure,
            VclCompiler compiler) {
        var active = ownerContext.current(candidate);
        var input = ownerContext.reviewedInput(active);
        if (input.sustained() == null
                || measure.kind() != KeyedStateStore.OutputKind.MEASURE
                || !active.account().equals(key.accountId())
                || !active.rule().equals(key.ruleId())
                || !active.epoch().equals(key.deploymentEpoch()))
            throw new VclException("WORKER_TARGET_INVALID", "");
        var value =
                new VclParser()
                        .readJson(measure.payload().payload().getBytes(StandardCharsets.UTF_8));
        var generation = input.start().inputGeneration();
        if (!DurableSustained.PROFILE.equals(value.path("profile").textValue())
                || !generation.toString().equals(value.path("generation").textValue())) {
            throw new VclException("SUSTAINED_MEASURE_CHANGED", "");
        }
        var window =
                new VclWindowPlan.Window(
                        java.time.Instant.parse(value.path("startedAt").textValue()),
                        java.time.Instant.parse(value.path("qualifiedAt").textValue()));
        boolean retraction = value.path("replayFrom").isTextual();
        var replay =
                retraction
                        ? new VclWindowPlan.Window(
                                java.time.Instant.parse(value.path("replayFrom").textValue()),
                                java.time.Instant.parse(value.path("replayThrough").textValue()))
                        : window;
        if (!active.includesWindow(replay.startInclusive())
                || !active.includesWindow(window.startInclusive())
                || replay.startInclusive().isAfter(window.startInclusive())
                || replay.endExclusive().isBefore(window.endExclusive())) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        var journal = input.start().atCheckpoint(value.path("checkpoint").textValue());
        var snapshot = input.snapshot(input.start());
        var plan = snapshot.prepareSustained(compiler, input.sustained());
        var timing = plan.timing();
        var due = timing.windowCloseDueAt(replay.endExclusive());
        if (store.databaseTime().isBefore(due)) return java.util.Optional.empty();
        UUID proofId =
                ownerContext.captures.findCoverage(
                        active.account(),
                        active.epoch(),
                        plan.binding(),
                        plan.schemaRevision(),
                        generation,
                        replay.startInclusive(),
                        due,
                        VclObservationReadiness.checkpoint(journal.capturedHeadCheckpoint()));
        var proof =
                proofId == null
                        ? null
                        : journal.coverage(proofId, replay.startInclusive(), due, true);
        if (proof == null || !proof.qualifiesEventTime()) {
            return idleSustained(
                    store,
                    journal,
                    timing,
                    window,
                    plan.binding(),
                    plan.schemaRevision(),
                    retraction);
        }
        var dependency =
                timing.requiredInputs().stream()
                        .filter(
                                dep ->
                                        dep.kind()
                                                        == com.voltcanary.vcl.VclMetadata.Resource
                                                                .BINDING
                                                && plan.binding().equals(dep.id())
                                                && dep.revision() == plan.schemaRevision())
                        .findFirst()
                        .orElseThrow(() -> new VclException("SUSTAINED_SOURCE_CHANGED", ""));
        var frontier =
                timing.watermark(
                        List.of(
                                new com.voltcanary.analytics.vcl.VclTimingPlan.Progress(
                                        dependency, proof.evidence().until(), proof.acceptedAt())),
                        timing.digest(),
                        snapshot.sessionScope());
        if (frontier.status() != com.voltcanary.analytics.vcl.ScalarValue.Status.OK
                || java.time.Instant.parse(frontier.literal().path("value").textValue())
                        .isBefore(replay.endExclusive())) {
            return idleSustained(
                    store,
                    journal,
                    timing,
                    window,
                    plan.binding(),
                    plan.schemaRevision(),
                    retraction);
        }
        var finalCut = journal.forFinalization(proof);
        var preview =
                snapshot.previewSustained(
                        plan,
                        finalCut,
                        key,
                        replay,
                        RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES);
        if (preview.result().value().isFailure())
            throw new VclException(preview.result().value().reason(), "");
        boolean confirmed =
                retraction
                        ? preview.result().unknownEvidence().isEmpty()
                                && preview.episodes().stream()
                                        .noneMatch(
                                                episode ->
                                                        episode.occurrenceDigest()
                                                                .equals(
                                                                        value.path("occurrence")
                                                                                .textValue()))
                        : preview.episodes().stream()
                                .anyMatch(
                                        episode ->
                                                episode.occurrenceDigest()
                                                                .equals(
                                                                        value.path("occurrence")
                                                                                .textValue())
                                                        && episode.firstObservation()
                                                                .toString()
                                                                .equals(
                                                                        value.path(
                                                                                        "firstObservation")
                                                                                .textValue())
                                                        && episode.qualifyingObservation()
                                                                .toString()
                                                                .equals(
                                                                        value.path(
                                                                                        "qualifyingObservation")
                                                                                .textValue())
                                                        && Long.toString(
                                                                        episode.coveredDurationUs())
                                                                .equals(
                                                                        value.path(
                                                                                        "coveredDurationUs")
                                                                                .textValue()));
        if (!proof.equals(finalCut.coverage(proofId, replay.startInclusive(), due))) {
            throw new VclException("SUSTAINED_COVERAGE_CHANGED", "");
        }
        ownerContext.current(active);
        var intent =
                snapshot.sessionAlertIntent(input.sustained(), active.bundleDigest()).orElse(null);
        return java.util.Optional.of(
                new SustainedFinalization(
                        window,
                        proof,
                        preview.inputDigest(),
                        confirmed,
                        retraction,
                        plan.binding(),
                        plan.schemaRevision(),
                        intent,
                        finalCut));
    }

    static java.util.Optional<SustainedFinalization> idleSustained(
            JdbcKeyedStateStore store,
            SourceCaptureSnapshot journal,
            com.voltcanary.analytics.vcl.VclTimingPlan timing,
            VclWindowPlan.Window window,
            UUID binding,
            long revision,
            boolean retraction) {
        return WorkerRuleCompilationSnapshots.sourceIdle(store, journal, timing)
                ? java.util.Optional.of(
                        new SustainedFinalization(
                                window,
                                null,
                                null,
                                false,
                                retraction,
                                binding,
                                revision,
                                null,
                                null))
                : java.util.Optional.empty();
    }
}
