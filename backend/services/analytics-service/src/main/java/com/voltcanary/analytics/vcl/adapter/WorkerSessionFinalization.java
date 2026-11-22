package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.DurableWindowSession;
import com.voltcanary.analytics.vcl.DurableZoneSession;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.VclSessionAbsenceKernel;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.adapter.WorkerRuleCompilationSnapshots.ReviewedInput;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceCoverage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

final class WorkerSessionFinalization {

    private final WorkerRuleCompilationSnapshots ownerContext;

    WorkerSessionFinalization(WorkerRuleCompilationSnapshots ownerContext) {
        this.ownerContext = ownerContext;
    }

    void timeoutSession(
        JdbcRuleDeployments.Deployment candidate,
        JdbcKeyedStateStore store,
        KeyedStateStore.DueTimer due,
        VclCompiler compiler,
        UUID holder
    ) {
        var active = ownerContext.current(candidate);
        var input = ownerContext.reviewedInput(active);
        if (input.session() == null) throw new VclException("SESSION_OPERATOR_REQUIRED", "");
        var head = input.start().atCurrentHead();
        long initial = VclObservationReadiness.checkpoint(head.generationStartCheckpoint());
        if (
            !store.sessionTimeoutReady(
                active,
                due,
                head.inputGeneration(),
                initial,
                VclObservationReadiness.checkpoint(head.capturedHeadCheckpoint())
            )
        ) {
            throw new VclException("SESSION_INPUT_PENDING", "");
        }
        var snapshot = input.snapshot(input.start());
        if (input.zone()) {
            var pin = com.voltcanary.analytics.vcl.DurableZoneSession.geometryPin(
                due.key(),
                due.scheduled().timer().payload()
            );
            snapshot
                .prepareZoneSession(compiler, input.session())
                .durable(
                    store,
                    active,
                    due.key(),
                    pin.zone(),
                    pin.revision(),
                    ownerContext.circles(),
                    () -> ownerContext.current(active)
                )
                .timeout(due, holder);
            return;
        }
        var session = snapshot
            .prepareWindowSession(compiler, input.session())
            .durable(store, active, due.key(), () -> ownerContext.current(active));
        session.timeout(due, holder);
    }

    void finishSessionAbsence(
        JdbcRuleDeployments.Deployment candidate,
        JdbcKeyedStateStore store,
        KeyedStateStore.DueTimer due,
        VclCompiler compiler,
        UUID holder
    ) {
        var active = ownerContext.current(candidate);
        var input = ownerContext.reviewedInput(active);
        var key = due.key();
        var saved = store.inspect(key, due.pinsDigest());
        var recipe = SessionAbsenceSchedule.recipe(due, saved.state());
        if (
            !recipe.node().equals(input.absence()) ||
            saved.revision() != due.scheduled().stateRevision()
        ) {
            throw new VclException("SESSION_WAIT_CHANGED", "");
        }
        var window = SessionAbsenceSchedule.window(key, saved.state(), input.zone());
        if (window == null) throw new VclException("SESSION_WAIT_CHANGED", "");
        var snapshot = input.snapshot(input.start());
        var pin = input.zone() ? DurableZoneSession.geometryPin(key, saved.state()) : null;
        var geometry =
            pin == null
                ? null
                : ownerContext
                      .circles()
                      .read(active, pin.zone(), pin.revision(), snapshot.sessionScope());
        var plan = snapshot.prepareSessionAbsence(
            compiler,
            recipe.node(),
            null,
            geometry,
            pin == null ? null : pin.zone()
        );
        var timer = due.scheduled().timer();
        if (!timer.dueAt().equals(plan.timing().windowCloseDueAt(window.endExclusive()))) {
            throw new VclException("SESSION_WAIT_CHANGED", "");
        }
        var boundary = active.start().boundary();
        if (active.resumeFrom() != null && active.resumeFrom().isAfter(boundary)) boundary =
            active.resumeFrom();
        var replayStart = plan.replayStart(window.startInclusive(), boundary);
        var coverageWindow =
            recipe.through() != null && recipe.through().isAfter(window.endExclusive())
                ? new VclWindowPlan.Window(window.startInclusive(), recipe.through())
                : window;
        long trigger = VclObservationReadiness.checkpoint(recipe.trigger());
        var facts = readSessionFacts(
            active,
            input,
            snapshot,
            plan,
            key,
            replayStart,
            coverageWindow,
            recipe.checkpoint(),
            trigger
        );
        var kernel = new VclSessionAbsenceKernel();
        var result = evaluateSession(kernel, plan, key, saved.state(), facts, input.zone());
        boolean waiting = false;
        if (
            replayOpen(result) ||
            VclObservationReadiness.checkpoint(facts.cut().throughCheckpoint()) < trigger
        ) {
            facts = snapshot.readSessionAbsenceInput(
                plan,
                key,
                plan.replayEnd(window.endExclusive()),
                replayStart,
                coverageWindow,
                recipe.checkpoint(),
                null,
                true
            );
            result = evaluateSession(kernel, plan, key, saved.state(), facts, input.zone());
            // Speculative replay identifies a boundary but cannot finalize a decision.
            waiting = result.window() == null;
        }
        // Every retry widens the requested proof and must retain the journal observations already
        // seen.
        for (
            int attempt = 0;
            !waiting &&
            result.window() != null &&
            (facts.proof() == null ||
                result.window().startInclusive().isBefore(coverageWindow.startInclusive()) ||
                result.window().endExclusive().isAfter(coverageWindow.endExclusive()));
            attempt++
        ) {
            if (attempt == 3) {
                waiting = true;
                break;
            }
            var corrected = result.window();
            coverageWindow = new VclWindowPlan.Window(
                corrected.startInclusive().isBefore(coverageWindow.startInclusive())
                    ? corrected.startInclusive()
                    : coverageWindow.startInclusive(),
                corrected.endExclusive().isAfter(coverageWindow.endExclusive())
                    ? corrected.endExclusive()
                    : coverageWindow.endExclusive()
            );
            var expanded = readSessionFacts(
                active,
                input,
                snapshot,
                plan,
                key,
                replayStart,
                coverageWindow,
                recipe.checkpoint(),
                Math.max(trigger, facts.lastRelevantCheckpoint())
            );
            if (
                expanded.proof() == null ||
                !expanded.proof().qualifiesEventTime() ||
                VclObservationReadiness.checkpoint(expanded.cut().throughCheckpoint()) <
                    Math.max(trigger, facts.lastRelevantCheckpoint())
            ) {
                waiting = true;
                break;
            }
            facts = expanded;
            result = evaluateSession(kernel, plan, key, saved.state(), facts, input.zone());
        }
        waiting |=
            replayOpen(result) || "SESSION_COVERAGE_UNKNOWN".equals(result.absence().reason());
        if (waiting && !sourceIdle(store, input.start(), plan.timing())) return;
        var absence = waiting
            ? com.voltcanary.analytics.vcl.ScalarValue.unknown("SESSION_SOURCE_IDLE", List.of())
            : result.absence();
        var measure = JsonNodeFactory.instance
            .objectNode()
            .put("profile", SessionAbsenceSchedule.MEASURE)
            .put("node", recipe.node())
            .put("checkpoint", facts.cut().throughCheckpoint())
            .put("closingCheckpoint", recipe.checkpoint())
            .put("status", absence.status().name())
            .put("reason", absence.reason());
        if (recipe.previous() != null) measure.put("supersedes", recipe.previous().toString());
        measure.set("literal", absence.literal());
        if (facts.cut().correctionRequired() != null) {
            measure.put("lateObservation", facts.cut().correctionRequired().toString());
        }
        if (result.window() != null) {
            measure
                .put("sessionStart", result.window().startInclusive().toString())
                .put("sessionEnd", result.window().endExclusive().toString())
                .put("entryObservation", result.entry().toString())
                .put("exitObservation", result.exit().toString());
        }
        var evidence = measure.putArray("evidence");
        absence.evidence().forEach(id -> evidence.add(id.toString()));
        if (!waiting && facts.proof() != null) measure.put(
            "proof",
            facts.proof().proofId().toString()
        );
        if (result.witness() != null && result.witness().firstObservationId() != null) {
            measure
                .put("firstWitness", result.witness().firstObservationId().toString())
                .put("lastWitness", result.witness().lastObservationId().toString())
                .put("coveredDurationUs", Long.toString(result.witness().coveredDurationUs()));
        }
        if (!result.positionWitnesses().isEmpty()) {
            var positions = measure.putArray("positionWitnesses");
            result.positionWitnesses().forEach(id -> positions.add(id.toString()));
        }
        var coverage = waiting ? null : facts.proof();
        String coverageDigest =
            coverage != null && coverage.qualifiesEventTime() ? coverage.eventTimeDigest() : null;
        if (coverageDigest != null) measure.put("coverageDigest", coverageDigest);
        String purpose = recipe.previous() == null ? "measure" : "measure:" + recipe.trigger();
        UUID measureId = SessionAbsenceSchedule.id(due.pinsDigest(), purpose);
        UUID alertBasis = sessionAlertBasis(store, due, recipe);
        var resultWindow = result.window();
        boolean coveredDecision =
            resultWindow != null &&
            coverageDigest != null &&
            absence.status() == com.voltcanary.analytics.vcl.ScalarValue.Status.OK;
        boolean matched = coveredDecision && absence.literal().path("value").booleanValue();
        boolean retraction =
            coveredDecision &&
            !matched &&
            alertBasis != null &&
            result.witness() != null &&
            result.witness().firstObservationId() != null;
        var intent =
            matched || retraction
                ? snapshot
                      .sessionAlertIntent(recipe.node(), active.bundleDigest())
                      .orElseThrow(() -> new VclException("SESSION_ALERT_INTENT_CHANGED", ""))
                : null;
        UUID nextBasis = matched ? measureId : retraction ? null : alertBasis;
        measure.put("alertBasis", nextBasis == null ? null : nextBasis.toString());
        var output = new KeyedStateStore.Output(
            measureId,
            KeyedStateStore.OutputKind.MEASURE,
            null,
            KeyedStateStore.State.parse(VclCanonical.encode(measure))
        );
        var outputs = new java.util.ArrayList<KeyedStateStore.Output>();
        outputs.add(output);
        String effectProfile = retraction
            ? SessionAlertProposal.RETRACTION
            : recipe.previous() == null
              ? SessionAlertProposal.PROFILE
              : SessionAlertProposal.CORRECTION;
        if (intent != null) {
            outputs.add(
                SessionAlertProposal.createSession(
                    key,
                    SessionAbsenceSchedule.id(due.pinsDigest(), "alert:" + purpose),
                    output,
                    resultWindow,
                    intent,
                    input.target().id(),
                    input.target().revision(),
                    coverage,
                    coverageDigest,
                    effectProfile,
                    measure,
                    input.policy().observationReferenceLimit()
                )
            );
        }
        var lease = store.acquire(key, due.pinsDigest(), holder);
        if (!lease.snapshot().equals(saved) || !lease.timers().equals(List.of(due.scheduled()))) {
            throw new VclException("SESSION_WAIT_CHANGED", "");
        }
        snapshot.reauthorize();
        if (coverageDigest != null) recheckCoverage(
            input.start(),
            coverage,
            "SESSION_COVERAGE_CHANGED"
        );
        store.commitSession(
            key,
            lease,
            new KeyedStateStore.Transition(
                timer.id(),
                saved.revision(),
                timer.digest(),
                saved.revision(),
                saved.state(),
                List.of(),
                new KeyedStateStore.TimerInput(timer.id(), saved.revision()),
                outputs
            ),
            recipe.openedAt(),
            active
        );
    }

    static UUID sessionAlertBasis(
        JdbcKeyedStateStore store,
        KeyedStateStore.DueTimer due,
        SessionAbsenceSchedule.Recipe recipe
    ) {
        if (recipe.previous() == null) return null;
        var prior = store.inspectOutput(due.key(), due.pinsDigest(), recipe.previous()).output();
        var measure = new VclParser().readJson(
            prior.payload().payload().getBytes(StandardCharsets.UTF_8)
        );
        if (
            prior.kind() != KeyedStateStore.OutputKind.MEASURE ||
            !SessionAbsenceSchedule.MEASURE.equals(measure.path("profile").textValue()) ||
            !recipe.node().equals(measure.path("node").textValue())
        ) {
            throw new VclException("SESSION_MEASURE_CHANGED", "");
        }
        var basis = measure.path("alertBasis");
        return basis.isTextual() ? WorkerRuleCompilationSnapshots.uuid(basis.textValue()) : null;
    }

    static boolean sourceIdle(
        JdbcKeyedStateStore store,
        SourceCaptureSnapshot journal,
        com.voltcanary.analytics.vcl.VclTimingPlan timing
    ) {
        var head = journal.atCurrentHead();
        var last = head.lastRecordedAt();
        var now = store.databaseTime().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return (
            !now.isBefore(last) &&
            timing.idleExpired(last, now) &&
            head.capturedHeadCheckpoint().equals(journal.atCurrentHead().capturedHeadCheckpoint())
        );
    }

    RuleCompilationSnapshot.SessionAbsenceInput readSessionFacts(
        JdbcRuleDeployments.Deployment active,
        ReviewedInput input,
        RuleCompilationSnapshot snapshot,
        VclSessionAbsenceKernel.Plan plan,
        KeyedStateStore.Key key,
        java.time.Instant replayStart,
        VclWindowPlan.Window coverageWindow,
        String closingCheckpoint,
        long minimumCheckpoint
    ) {
        UUID proof = ownerContext.captures.findCoverage(
            active.account(),
            active.epoch(),
            input.target().id(),
            input.target().revision(),
            input.start().inputGeneration(),
            coverageWindow.startInclusive(),
            plan.timing().windowCloseDueAt(coverageWindow.endExclusive()),
            minimumCheckpoint
        );
        return snapshot.readSessionAbsenceInput(
            plan,
            key,
            coverageWindow.endExclusive(),
            replayStart,
            coverageWindow,
            closingCheckpoint,
            proof,
            false
        );
    }

    static boolean replayOpen(VclSessionAbsenceKernel.Result result) {
        return "SESSION_REPLAY_OPEN".equals(result.absence().reason());
    }

    static VclSessionAbsenceKernel.Result evaluateSession(
        VclSessionAbsenceKernel kernel,
        VclSessionAbsenceKernel.Plan plan,
        KeyedStateStore.Key key,
        KeyedStateStore.State state,
        RuleCompilationSnapshot.SessionAbsenceInput facts,
        boolean zone
    ) {
        return zone
            ? kernel.evaluate(
                  plan,
                  DurableZoneSession.sessionState(key, state),
                  facts.cut(),
                  facts.proof(),
                  plan.scope()
              )
            : kernel.evaluate(
                  plan,
                  DurableWindowSession.decode(key, state),
                  facts.cut(),
                  facts.proof(),
                  plan.scope()
              );
    }

    static void recheckCoverage(
        SourceCaptureSnapshot journal,
        VclSourceCoverage proof,
        String error
    ) {
        if (
            !proof.equals(
                journal.coverage(
                    proof.proofId(),
                    proof.evidence().from(),
                    proof.evidence().until(),
                    true
                )
            )
        ) {
            throw new VclException(error, "");
        }
    }
}
