package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.adapter.WorkerRuleCompilationSnapshots.ReviewedInput;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

final class WorkerIntervalFinalization {

    private final WorkerRuleCompilationSnapshots ownerContext;

    WorkerIntervalFinalization(WorkerRuleCompilationSnapshots ownerContext) {
        this.ownerContext = ownerContext;
    }

    void finishIntervalWindow(
        JdbcRuleDeployments.Deployment candidate,
        JdbcKeyedStateStore store,
        KeyedStateStore.DueTimer due,
        VclCompiler compiler,
        UUID holder
    ) {
        var active = ownerContext.current(candidate);
        var input = ownerContext.reviewedInput(active);
        var key = due.key();
        var scheduled = due.scheduled();
        var timer = scheduled.timer();
        var recipe = IntervalWindowSchedule.recipe(timer.payload());
        boolean count = recipe.node().equals(input.aggregate()) && input.interval() == null;
        if (
            (!count && !recipe.node().equals(input.interval())) ||
            !recipe.generation().equals(input.start().inputGeneration()) ||
            !key.occurrenceDigest().equals(IntervalWindowSchedule.occurrence(recipe.window())) ||
            !due.pinsDigest().equals(IntervalWindowSchedule.pins(key, active, recipe)) ||
            !timer.equals(IntervalWindowSchedule.timer(due.pinsDigest(), recipe))
        ) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        var journal = input.start();
        var snapshot = input.snapshot(journal);
        var restored = input.references() ? ownerContext.windowReferences(active, compiler) : null;
        var countBatch = !count
            ? null
            : restored != null
              ? restored.lookups().journalAggregate(recipe.node())
              : snapshot.prepareJournalWindowBatch(
                    compiler,
                    recipe.node(),
                    64L * com.voltcanary.analytics.vcl.VclWindowAggregateKernel.MAX_INPUTS,
                    true
                );
        var subject =
            recipe.subjectLiteral() == null
                ? null
                : com.voltcanary.analytics.vcl.ScalarValue.known(
                      new VclParser(),
                      recipe.subjectLiteral()
                  );
        if (
            subject != null &&
            !key
                .subjectDigest()
                .equals(
                    count
                        ? countBatch.aggregateSubjectDigest(subject)
                        : com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(subject)
                )
        ) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        if (
            count &&
            (subject == null || (recipe.prefixIndex() > 0 && !countBatch.usesCountPrefixes()))
        ) {
            throw new VclException("WINDOW_LOWER_BOUND_UNAVAILABLE", "");
        }
        String planDigest = count
            ? countBatch.evaluatorPlanDigest()
            : restored == null
              ? snapshot.prepareIntervalUnion(compiler, recipe.node()).digest()
              : restored.lookups().intervalPlanDigest(recipe.node());
        if (!recipe.planDigest().equals(planDigest)) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        var previous =
            recipe.previousFinal() == null
                ? null
                : store.inspectOutput(key, due.pinsDigest(), recipe.previousFinal()).output();
        var prior =
            previous == null
                ? null
                : new VclParser().readJson(
                      previous.payload().payload().getBytes(StandardCharsets.UTF_8)
                  );
        if (
            previous != null &&
            (previous.kind() != KeyedStateStore.OutputKind.MEASURE ||
                !IntervalWindowSchedule.terminalMeasure(prior) ||
                !recipe
                    .window()
                    .startInclusive()
                    .toString()
                    .equals(prior.path("windowStart").textValue()) ||
                !recipe
                    .window()
                    .endExclusive()
                    .toString()
                    .equals(prior.path("windowEnd").textValue()))
        ) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        var referenceOverride = prior == null ? null : prior.get("referenceOverride");
        if (referenceOverride != null) {
            if (restored == null || ownerContext.checkpoints == null) throw new VclException(
                "INTERVAL_REFERENCE_CHANGED",
                ""
            );
            restored = ownerContext.checkpoints.restoreWindowReferences(
                active,
                compiler,
                RuleStartCheckpointRecipe.referencePins(referenceOverride)
            );
            if (count) countBatch = restored.lookups().journalAggregate(recipe.node());
        }
        java.util.Optional<RuleCompilationSnapshot.IntervalFinalization> finished =
            java.util.Optional.empty();
        com.fasterxml.jackson.databind.JsonNode countDecision = null;
        if (count) {
            var proof = countBatch.evaluateCovered(
                subject,
                recipe.window(),
                recipe.measuredThrough(),
                recipe.minimumCheckpoint(),
                intervalProof(active, input, recipe, timer.dueAt())
            );
            if (proof.isEmpty() && recipe.prefixIndex() > 0) {
                proof = countBatch.evaluateCovered(
                    subject,
                    recipe.window(),
                    recipe.window().endExclusive(),
                    recipe.minimumCheckpoint(),
                    intervalProof(active, input, recipe, recipe.dueAt())
                );
            }
            if (proof.isPresent()) {
                var result = proof.get();
                countDecision = result.decision();
                finished = java.util.Optional.of(
                    new RuleCompilationSnapshot.IntervalFinalization(
                        new RuleCompilationSnapshot.WindowPreview(
                            result.inputDigest(),
                            result.value()
                        ),
                        result.proof(),
                        result.cut().digest(),
                        result.cut().head(),
                        result.through()
                    )
                );
            }
        }
        if (!count && recipe.prefixIndex() > 0) {
            var prefixProof = intervalProof(active, input, recipe, timer.dueAt());
            finished =
                restored == null
                    ? snapshot.proveDirectIntervalPrefix(
                          compiler,
                          recipe.node(),
                          key.subjectDigest(),
                          recipe.window(),
                          recipe.measuredThrough(),
                          recipe.minimumCheckpoint(),
                          prefixProof
                      )
                    : restored
                          .lookups()
                          .proveIntervalPrefix(
                              recipe.node(),
                              key.subjectDigest(),
                              recipe.window(),
                              recipe.measuredThrough(),
                              restored.lookups().referenceStartPins(),
                              recipe.minimumCheckpoint(),
                              prefixProof
                          );
        }
        if (!count && finished.isEmpty()) {
            var closeProof = intervalProof(active, input, recipe, recipe.dueAt());
            finished =
                restored == null
                    ? snapshot.finalizeDirectIntervalUnion(
                          compiler,
                          recipe.node(),
                          key.subjectDigest(),
                          recipe.window(),
                          recipe.minimumCheckpoint(),
                          closeProof
                      )
                    : restored
                          .lookups()
                          .finalizeIntervalUnion(
                              recipe.node(),
                              key.subjectDigest(),
                              recipe.window(),
                              restored.lookups().referenceStartPins(),
                              recipe.minimumCheckpoint(),
                              closeProof
                          );
        }
        var finalization = finished.orElse(null);
        boolean idle = finalization == null;
        if (idle) {
            var timing = com.voltcanary.analytics.vcl.VclTimingPlan.compile(
                restored == null ? snapshot.compile(compiler) : restored.lookups().compilation()
            );
            if (
                store.databaseTime().isBefore(recipe.dueAt()) ||
                !WorkerRuleCompilationSnapshots.sourceIdle(store, input.start(), timing)
            ) return;
        }
        var result = idle
            ? com.voltcanary.analytics.vcl.ScalarValue.unknown("WINDOW_SOURCE_IDLE", List.of())
            : finalization.result().localValue();
        boolean complete =
            idle || finalization.measuredThrough().equals(recipe.window().endExclusive());
        String coverageDigest = idle ? null : finalization.proof().eventTimeDigest();
        var measure = JsonNodeFactory.instance
            .objectNode()
            .put("profile", "vcl-interval-final-measure-1")
            .put("node", recipe.node())
            .put("finalized", complete && !idle)
            .put("windowStart", recipe.window().startInclusive().toString())
            .put("windowEnd", recipe.window().endExclusive().toString())
            .put("status", result.status().name())
            .put("reason", result.reason());
        if (idle) {
            measure.put(
                "checkpoint",
                Long.toString(
                    Math.max(
                        recipe.minimumCheckpoint(),
                        VclObservationReadiness.checkpoint(journal.capturedHeadCheckpoint())
                    )
                )
            );
        } else {
            measure
                .put("inputDigest", finalization.result().inputDigest())
                .put("cutDigest", finalization.cutDigest())
                .put("checkpoint", finalization.checkpoint())
                .put("proof", finalization.proof().proofId().toString())
                .put("coverageDigest", coverageDigest);
        }
        if (!complete) measure.put("measuredThrough", finalization.measuredThrough().toString());
        if (recipe.previousFinal() != null) measure.put(
            "supersedes",
            recipe.previousFinal().toString()
        );
        if (referenceOverride != null) measure.set("referenceOverride", referenceOverride);
        measure.set("literal", result.literal());
        var evidence = measure.putArray("evidence");
        result.evidence().forEach(id -> evidence.add(id.toString()));
        DurableJournalWindow.AlertIntent intent = null;
        boolean retract = false;
        UUID alertBasis = recipe.earlyMeasure();
        if (previous != null) {
            alertBasis = prior.path("alertBasis").isTextual()
                ? UUID.fromString(prior.path("alertBasis").textValue())
                : prior.at("/decision/literal/value").booleanValue()
                  ? previous.id()
                  : null;
        }
        if (!idle && subject != null) {
            var decision = count
                ? null
                : restored == null
                  ? snapshot.intervalDecision(compiler, recipe.node(), subject)
                  : restored.lookups().intervalDecision(recipe.node(), subject);
            var verdict = count
                ? countDecision
                : complete
                  ? decision.evaluate(result)
                  : decision.evaluateLowerBound(result);
            measure.set("decision", verdict);
            if (
                result.status() == com.voltcanary.analytics.vcl.ScalarValue.Status.OK &&
                "OK".equals(verdict.path("status").textValue()) &&
                verdict.at("/literal/value").isBoolean()
            ) {
                boolean matched = verdict.at("/literal/value").booleanValue();
                retract = complete && !matched && alertBasis != null;
                if (retract) {
                    if (recipe.previousFinal() == null) {
                        verifyIntervalEarlyMeasure(store, key, due.pinsDigest(), recipe);
                        measure.put("supersedes", recipe.earlyMeasure().toString());
                    }
                    alertBasis = null;
                }
                if (matched || retract) {
                    intent = snapshot
                        .sessionAlertIntent(
                            verdict.path("nodeId").textValue(),
                            active.bundleDigest()
                        )
                        .orElseThrow(() -> new VclException("EVALUATOR_PROFILE_UNAVAILABLE", ""));
                }
            }
        }
        String purpose =
            recipe.previousFinal() != null
                ? "corrected-measure:" + recipe.previousFinal()
                : complete
                  ? "measure"
                  : "prefix-measure:" + recipe.prefixIndex();
        UUID outputId = IntervalWindowSchedule.id(due.pinsDigest(), purpose);
        if (intent != null && !retract) alertBasis = outputId;
        if (alertBasis != null) measure.put("alertBasis", alertBasis.toString());
        var output = new KeyedStateStore.Output(
            outputId,
            KeyedStateStore.OutputKind.MEASURE,
            null,
            KeyedStateStore.State.parse(VclCanonical.encode(measure))
        );
        var outputs = new java.util.ArrayList<KeyedStateStore.Output>();
        outputs.add(output);
        if (complete && intent != null) {
            String alertId =
                recipe.previousFinal() == null
                    ? "alert"
                    : "corrected-alert:" + recipe.previousFinal();
            String profile = count
                ? retract
                    ? SessionAlertProposal.AGGREGATE_RETRACTION
                    : recipe.previousFinal() == null
                      ? SessionAlertProposal.AGGREGATE
                      : SessionAlertProposal.AGGREGATE_CORRECTION
                : retract
                  ? SessionAlertProposal.INTERVAL_RETRACTION
                  : recipe.previousFinal() == null
                    ? SessionAlertProposal.INTERVAL
                    : SessionAlertProposal.INTERVAL_CORRECTION;
            outputs.add(
                SessionAlertProposal.create(
                    key,
                    IntervalWindowSchedule.id(due.pinsDigest(), alertId),
                    output,
                    recipe.window(),
                    intent,
                    input.target().id(),
                    input.target().revision(),
                    finalization.proof(),
                    coverageDigest,
                    profile
                )
            );
        } else if (intent != null) {
            outputs.add(
                SessionAlertProposal.createIntervalPrefix(
                    key,
                    IntervalWindowSchedule.id(
                        due.pinsDigest(),
                        "prefix-alert:" + recipe.prefixIndex()
                    ),
                    output,
                    recipe.window(),
                    intent,
                    input.target().id(),
                    input.target().revision(),
                    finalization.proof(),
                    coverageDigest,
                    finalization.measuredThrough(),
                    count
                )
            );
        }
        var lease = store.acquire(key, due.pinsDigest(), holder);
        if (
            !lease.timers().equals(List.of(scheduled)) ||
            !lease.snapshot().state().equals(timer.payload())
        ) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        var next = complete ? recipe : recipe.next(intent == null ? null : output.id());
        var timers = complete
            ? List.<KeyedStateStore.Timer>of()
            : List.of(IntervalWindowSchedule.timer(due.pinsDigest(), next));
        snapshot.reauthorize();
        if (!idle) {
            WorkerRuleCompilationSnapshots.recheckCoverage(
                journal,
                finalization.proof(),
                "INTERVAL_COVERAGE_CHANGED"
            );
        }
        var transition = new KeyedStateStore.Transition(
            timer.id(),
            scheduled.stateRevision(),
            timer.digest(),
            lease.snapshot().revision(),
            next.state(),
            timers,
            new KeyedStateStore.TimerInput(timer.id(), scheduled.stateRevision()),
            outputs
        );
        if (count && !idle) {
            store.commitCoveredAggregate(
                key,
                lease,
                transition,
                recipe.window().startInclusive(),
                active,
                countBatch.coveredFrontier(
                    key,
                    recipe.window(),
                    finalization.measuredThrough(),
                    finalization.proof(),
                    coverageDigest
                )
            );
        } else {
            store.commitSession(key, lease, transition, recipe.window().startInclusive(), active);
        }
    }

    static void verifyIntervalEarlyMeasure(
        JdbcKeyedStateStore store,
        KeyedStateStore.Key key,
        String pins,
        IntervalWindowSchedule.Recipe recipe
    ) {
        var output = store.inspectOutput(key, pins, recipe.earlyMeasure()).output();
        var earlier = new VclParser().readJson(
            output.payload().payload().getBytes(StandardCharsets.UTF_8)
        );
        if (
            output.kind() != KeyedStateStore.OutputKind.MEASURE ||
            !"vcl-interval-final-measure-1".equals(earlier.path("profile").textValue()) ||
            !recipe.node().equals(earlier.path("node").textValue()) ||
            !recipe
                .window()
                .startInclusive()
                .toString()
                .equals(earlier.path("windowStart").textValue()) ||
            !recipe
                .window()
                .endExclusive()
                .toString()
                .equals(earlier.path("windowEnd").textValue()) ||
            !earlier.path("finalized").isBoolean() ||
            earlier.path("finalized").booleanValue() ||
            !"OK".equals(earlier.at("/decision/status").textValue()) ||
            !earlier.at("/decision/literal/value").isBoolean() ||
            !earlier.at("/decision/literal/value").booleanValue()
        ) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
    }

    java.util.function.BiFunction<java.time.Instant, java.time.Instant, UUID> intervalProof(
        JdbcRuleDeployments.Deployment active,
        ReviewedInput input,
        IntervalWindowSchedule.Recipe recipe,
        java.time.Instant expectedDue
    ) {
        return (from, until) -> {
            if (!until.equals(expectedDue)) throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
            return ownerContext.captures.findCoverage(
                active.account(),
                active.epoch(),
                input.target().id(),
                input.target().revision(),
                recipe.generation(),
                from,
                until,
                recipe.minimumCheckpoint()
            );
        };
    }
}
