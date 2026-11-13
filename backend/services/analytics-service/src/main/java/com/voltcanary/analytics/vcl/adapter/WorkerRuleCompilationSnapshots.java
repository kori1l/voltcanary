package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.DurableWindowSession;
import com.voltcanary.analytics.vcl.DurableZoneSession;
import com.voltcanary.analytics.vcl.DurableSustained;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.VclSessionAbsenceKernel;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceCoverage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** Rebuilds one direct-journal diagnostic window from its durable recipe under machine authority. */
public final class WorkerRuleCompilationSnapshots {
    private final JdbcCompilationBundleStore bundles;
    private final JdbcRuleDeployments deployments;
    private final WorkerSourceCaptureSnapshots captures;
    private final WorkerGrantReads grants;
    private final WorkerPolicyReads policies;
    private final WorkerCircleReads circles;
    private final WorkerStartCheckpoints checkpoints;

    public WorkerRuleCompilationSnapshots(DataSource source, WorkerSourceCaptureSnapshots captures,
            WorkerGrantReads grants, WorkerPolicyReads policies) {
        this(source, captures, grants, policies, null, null);
    }

    WorkerRuleCompilationSnapshots(DataSource source, WorkerSourceCaptureSnapshots captures,
            WorkerGrantReads grants, WorkerPolicyReads policies, WorkerCircleReads circles, WorkerStartCheckpoints checkpoints) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        this.captures = captures;
        this.grants = grants;
        this.policies = policies;
        this.circles = circles;
        this.checkpoints = checkpoints;
    }

    WorkerRuleCompilationSnapshots(JdbcCompilationBundleStore bundles, JdbcRuleDeployments deployments,
            WorkerSourceCaptureSnapshots captures, WorkerGrantReads grants, WorkerPolicyReads policies) {
        this.bundles = bundles;
        this.deployments = deployments;
        this.captures = captures;
        this.grants = grants;
        this.policies = policies;
        circles = null;
        checkpoints = null;
    }

    List<JdbcRuleDeployments.Deployment> openingCandidates(int limit, int offset) {
        return deployments.journalPage(limit, offset);
    }

    void openPage(JdbcRuleDeployments.Deployment candidate, JdbcKeyedStateStore store, VclCompiler compiler, UUID holder) {
        var active = current(candidate);
        if (active.start() == null) return;
        var input = reviewedInput(active);
        boolean draining = active.state() == JdbcRuleDeployments.State.DRAINING;
        if (draining && input.session() == null) return;
        var latest = input.start().atCurrentHead();
        UUID generation = latest.inputGeneration();
        long initial = com.voltcanary.vcl.VclObservationReadiness.checkpoint(latest.generationStartCheckpoint());
        long after = store.openingCheckpoint(active, generation, initial);
        long head = com.voltcanary.vcl.VclObservationReadiness.checkpoint(latest.capturedHeadCheckpoint());
        if (after > head) throw new VclException("CAPTURE_CHECKPOINT_REGRESSED", "");
        if (after == head) return;
        Long referenceRevision = input.references() ? store.referenceCorrectionRevision(active) : null;
        var page = latest.readPage(Long.toString(after), 8);
        for (var observation : page.observations()) {
            String checkpoint = observation.path("checkpoint").textValue();
            long next = VclObservationReadiness.checkpoint(checkpoint);
            openObservation(active, input, latest, observation, checkpoint, next, store, compiler, holder, draining);
            if (!store.advanceOpeningCheckpoint(active, generation, initial, after, next, referenceRevision)) return;
            after = next;
        }
    }

    private void openObservation(JdbcRuleDeployments.Deployment active, ReviewedInput input, SourceCaptureSnapshot latest,
            JsonNode observation, String checkpoint, long next, JdbcKeyedStateStore store, VclCompiler compiler, UUID holder,
            boolean draining) {
        var references = input.references() ? windowReferences(active, compiler).lookups() : null;
        if (input.session() != null) {
            var snapshot = input.snapshot(latest.atCheckpoint(checkpoint));
            if (input.zone()) {
                var session = snapshot.prepareZoneSession(compiler, input.session());
                session.route(active.epoch(), observation).filter(candidate ->
                        active.includesSessionObservation(candidate.value().eventTime())).ifPresent(candidate -> {
                            var value = candidate.value();
                            var pinned = store.zonePin(value.key());
                            if (pinned.isEmpty() && (draining || !candidate.included() || value.position() == null
                                    || store.drainingOccurrence(active, value.key()))) return;
                            UUID zone = pinned.map(com.voltcanary.analytics.vcl.DurableZoneSession.GeometryPin::zone).orElse(value.zone());
                            if (zone != null) session.durable(store, active, value.key(), zone,
                                    pinned.map(com.voltcanary.analytics.vcl.DurableZoneSession.GeometryPin::revision).orElse(0L),
                                    circles(), () -> current(active), input.absence(), checkpoint,
                                    value.eventTime()).observe(value, holder,
                                            (from, through) -> session.replayHistory(active, value.key(), from, through),
                                            candidate.included());
                        });
            } else {
                var session = snapshot.prepareWindowSession(compiler, input.session());
                session.route(active.epoch(), observation).filter(value ->
                        active.includesSessionObservation(value.value().eventTime())
                                && (draining || !value.included() ? store.hasCommittedState(value.key())
                                        : !store.drainingOccurrence(active, value.key()))).ifPresent(value ->
                                session.durable(store, active, value.key(), () -> current(active),
                                        input.absence(), checkpoint, value.value().eventTime()).observe(value.value(), holder,
                                                (from, through) -> session.replayHistory(active, value.key(), from, through),
                                                value.included()));
            }
            snapshot.reauthorize();
        } else if (input.interval() != null) {
            if (references != null) {
                references.stageIntervalWindows(input.interval(), input.target(), active, store, observation, holder);
            } else {
                var snapshot = input.snapshot(input.start());
                snapshot.stageIntervalWindows(compiler, input.interval(), input.target(), active, store, observation, holder);
                snapshot.reauthorize();
            }
        } else if (input.sustained() != null) {
            var snapshot = input.snapshot(latest.atCheckpoint(checkpoint));
            snapshot.observeSustained(compiler, input.sustained(), active, store, observation, holder,
                    () -> current(active));
            snapshot.reauthorize();
        } else {
            var snapshot = input.snapshot(latest.atCheckpoint(checkpoint));
            var batch = references != null ? references.journalAggregate(input.aggregate())
                    : snapshot.prepareJournalWindowBatch(compiler, input.aggregate(),
                            64L * com.voltcanary.analytics.vcl.VclWindowAggregateKernel.MAX_INPUTS, true);
            for (var opening : batch.openings(observation)) {
                if (!active.includesWindow(opening.window().startInclusive())) continue;
                if (batch.usesCountPrefixes() || input.references() || !opening.textSubject()) {
                    batch.stageAggregateWindow(store, active, opening, next, observation, holder);
                    continue;
                }
                var staged = batch.durable(store, active.epoch(), opening.subject(), opening.window(), null,
                        () -> current(active), (key, lease, transition, watermark) -> store.commitJournalWindow(
                                key, lease, transition, opening.window().startInclusive(), active, watermark), active.bundleDigest());
                if (!store.hasStagedWindow(staged.key(), opening.subject(), opening.window())) {
                    batch.stageAggregateWindow(store, active, opening, next, observation, holder);
                }
            }
            snapshot.reauthorize();
        }
        if (references != null) {
            var snapshot = input.snapshot(latest.atCheckpoint(checkpoint));
            var timing = com.voltcanary.analytics.vcl.VclTimingPlan.compile(references.compilation());
            var eventTime = java.time.Instant.parse(observation.path("eventTime").textValue());
            var recordedAt = java.time.Instant.parse(observation.path("recordedAt").textValue());
            if (timing.classify(eventTime, recordedAt)
                    != com.voltcanary.analytics.vcl.VclTimingPlan.Admission.QUARANTINED_FUTURE) {
                var range = RuleCompilationSnapshot.correctionRange(observation, latest, input.target(), input.interval() != null);
                for (var corrected : store.correctedWindows(active, range.startInclusive(), range.endExclusive())) {
                    snapshot.reauthorize();
                    IntervalWindowSchedule.stageCorrected(store, active, corrected, next, holder);
                }
            }
        }
    }

    void finishIntervalWindow(JdbcRuleDeployments.Deployment candidate, JdbcKeyedStateStore store,
            KeyedStateStore.DueTimer due, VclCompiler compiler, UUID holder) {
        var active = current(candidate);
        var input = reviewedInput(active);
        var key = due.key();
        var scheduled = due.scheduled();
        var timer = scheduled.timer();
        var recipe = IntervalWindowSchedule.recipe(timer.payload());
        boolean count = recipe.node().equals(input.aggregate()) && input.interval() == null;
        if ((!count && !recipe.node().equals(input.interval())) || !recipe.generation().equals(input.start().inputGeneration())
                || !key.occurrenceDigest().equals(IntervalWindowSchedule.occurrence(recipe.window()))
                || !due.pinsDigest().equals(IntervalWindowSchedule.pins(key, active, recipe))
                || !timer.equals(IntervalWindowSchedule.timer(due.pinsDigest(), recipe))) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        var journal = input.start();
        if (count && recipe.minimumCheckpoint() > VclObservationReadiness.checkpoint(journal.capturedHeadCheckpoint())) {
            journal = journal.atCheckpoint(Long.toString(recipe.minimumCheckpoint()));
        }
        var snapshot = input.snapshot(journal);
        var restored = input.references() ? windowReferences(active, compiler) : null;
        var countBatch = !count ? null : restored != null ? restored.lookups().journalAggregate(recipe.node())
                : snapshot.prepareJournalWindowBatch(compiler, recipe.node(),
                        64L * com.voltcanary.analytics.vcl.VclWindowAggregateKernel.MAX_INPUTS, true);
        var subject = recipe.subjectLiteral() == null ? null
                : com.voltcanary.analytics.vcl.ScalarValue.known(new VclParser(), recipe.subjectLiteral());
        if (subject != null && !key.subjectDigest().equals(count ? countBatch.aggregateSubjectDigest(subject)
                : com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(subject))) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        if (count && (subject == null || recipe.prefixIndex() > 0 && !countBatch.usesCountPrefixes())) {
            throw new VclException("WINDOW_LOWER_BOUND_UNAVAILABLE", "");
        }
        String planDigest = count ? countBatch.evaluatorPlanDigest()
                : restored == null ? snapshot.prepareIntervalUnion(compiler, recipe.node()).digest()
                : restored.lookups().intervalPlanDigest(recipe.node());
        if (!recipe.planDigest().equals(planDigest)) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        var previous = recipe.previousFinal() == null ? null
                : store.inspectOutput(key, due.pinsDigest(), recipe.previousFinal()).output();
        var prior = previous == null ? null : new VclParser().readJson(previous.payload().payload().getBytes(StandardCharsets.UTF_8));
        if (previous != null && (previous.kind() != KeyedStateStore.OutputKind.MEASURE
                || !IntervalWindowSchedule.terminalMeasure(prior)
                || !recipe.window().startInclusive().toString().equals(prior.path("windowStart").textValue())
                || !recipe.window().endExclusive().toString().equals(prior.path("windowEnd").textValue()))) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        var referenceOverride = prior == null ? null : prior.get("referenceOverride");
        if (referenceOverride != null) {
            if (restored == null || checkpoints == null) throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
            restored = checkpoints.restoreWindowReferences(active, compiler, RuleStartCheckpointRecipe.referencePins(referenceOverride));
            if (count) countBatch = restored.lookups().journalAggregate(recipe.node());
        }
        java.util.Optional<RuleCompilationSnapshot.IntervalFinalization> finished = java.util.Optional.empty();
        com.fasterxml.jackson.databind.JsonNode countDecision = null;
        if (count) {
            var proof = countBatch.evaluateCovered(subject, recipe.window(), recipe.measuredThrough(), recipe.minimumCheckpoint(),
                    intervalProof(active, input, recipe, timer.dueAt()));
            if (proof.isEmpty() && recipe.prefixIndex() > 0) {
                proof = countBatch.evaluateCovered(subject, recipe.window(), recipe.window().endExclusive(), recipe.minimumCheckpoint(),
                        intervalProof(active, input, recipe, recipe.dueAt()));
            }
            if (proof.isPresent()) {
                var result = proof.get();
                countDecision = result.decision();
                finished = java.util.Optional.of(new RuleCompilationSnapshot.IntervalFinalization(
                        new RuleCompilationSnapshot.WindowPreview(result.inputDigest(), result.value()),
                        result.proof(), result.cut().digest(), result.cut().head(), result.through()));
            }
        }
        if (!count && recipe.prefixIndex() > 0) {
            var prefixProof = intervalProof(active, input, recipe, timer.dueAt());
            finished = restored == null ? snapshot.proveDirectIntervalPrefix(compiler, recipe.node(), key.subjectDigest(),
                    recipe.window(), recipe.measuredThrough(), recipe.minimumCheckpoint(), prefixProof)
                    : restored.lookups().proveIntervalPrefix(recipe.node(), key.subjectDigest(), recipe.window(),
                            recipe.measuredThrough(), restored.lookups().referenceStartPins(), recipe.minimumCheckpoint(), prefixProof);
        }
        if (!count && finished.isEmpty()) {
            var closeProof = intervalProof(active, input, recipe, recipe.dueAt());
            finished = restored == null
                    ? snapshot.finalizeDirectIntervalUnion(compiler, recipe.node(), key.subjectDigest(), recipe.window(),
                            recipe.minimumCheckpoint(), closeProof)
                    : restored.lookups().finalizeIntervalUnion(recipe.node(), key.subjectDigest(), recipe.window(),
                            restored.lookups().referenceStartPins(), recipe.minimumCheckpoint(), closeProof);
        }
        var finalization = finished.orElse(null);
        boolean idle = finalization == null;
        if (idle) {
            var timing = com.voltcanary.analytics.vcl.VclTimingPlan.compile(restored == null ? snapshot.compile(compiler)
                    : restored.lookups().compilation());
            if (store.databaseTime().isBefore(recipe.dueAt()) || !sourceIdle(store, input.start(), timing)) return;
        }
        var result = idle ? com.voltcanary.analytics.vcl.ScalarValue.unknown("WINDOW_SOURCE_IDLE", List.of())
                : finalization.result().localValue();
        boolean complete = idle || finalization.measuredThrough().equals(recipe.window().endExclusive());
        String coverageDigest = idle ? null : finalization.proof().eventTimeDigest();
        var measure = JsonNodeFactory.instance.objectNode().put("profile", "vcl-interval-final-measure-1")
                .put("node", recipe.node()).put("finalized", complete && !idle)
                .put("windowStart", recipe.window().startInclusive().toString())
                .put("windowEnd", recipe.window().endExclusive().toString())
                .put("status", result.status().name()).put("reason", result.reason());
        if (idle) {
            measure.put("checkpoint", Long.toString(Math.max(recipe.minimumCheckpoint(),
                    VclObservationReadiness.checkpoint(journal.capturedHeadCheckpoint()))));
        } else {
            measure.put("inputDigest", finalization.result().inputDigest()).put("cutDigest", finalization.cutDigest())
                    .put("checkpoint", finalization.checkpoint()).put("proof", finalization.proof().proofId().toString())
                    .put("coverageDigest", coverageDigest);
        }
        if (!complete) measure.put("measuredThrough", finalization.measuredThrough().toString());
        if (recipe.previousFinal() != null) measure.put("supersedes", recipe.previousFinal().toString());
        if (referenceOverride != null) measure.set("referenceOverride", referenceOverride);
        measure.set("literal", result.literal());
        var evidence = measure.putArray("evidence");
        result.evidence().forEach(id -> evidence.add(id.toString()));
        DurableJournalWindow.AlertIntent intent = null;
        boolean retract = false;
        UUID alertBasis = recipe.earlyMeasure();
        if (previous != null) {
            alertBasis = prior.path("alertBasis").isTextual() ? UUID.fromString(prior.path("alertBasis").textValue())
                    : prior.at("/decision/literal/value").booleanValue() ? previous.id() : null;
        }
        if (!idle && subject != null) {
            var decision = count ? null : restored == null ? snapshot.intervalDecision(compiler, recipe.node(), subject)
                    : restored.lookups().intervalDecision(recipe.node(), subject);
            var verdict = count ? countDecision : (complete ? decision.evaluate(result) : decision.evaluateLowerBound(result));
            measure.set("decision", verdict);
            if (result.status() == com.voltcanary.analytics.vcl.ScalarValue.Status.OK
                    && "OK".equals(verdict.path("status").textValue()) && verdict.at("/literal/value").isBoolean()) {
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
                    intent = snapshot.sessionAlertIntent(verdict.path("nodeId").textValue(), active.bundleDigest()).orElseThrow(
                            () -> new VclException("EVALUATOR_PROFILE_UNAVAILABLE", ""));
                }
            }
        }
        String purpose = recipe.previousFinal() != null ? "corrected-measure:" + recipe.previousFinal()
                : complete ? "measure" : "prefix-measure:" + recipe.prefixIndex();
        UUID outputId = IntervalWindowSchedule.id(due.pinsDigest(), purpose);
        if (intent != null && !retract) alertBasis = outputId;
        if (alertBasis != null) measure.put("alertBasis", alertBasis.toString());
        var output = new KeyedStateStore.Output(outputId,
                KeyedStateStore.OutputKind.MEASURE, null, KeyedStateStore.State.parse(VclCanonical.encode(measure)));
        var outputs = new java.util.ArrayList<KeyedStateStore.Output>();
        outputs.add(output);
        if (complete && intent != null) {
            String alertId = recipe.previousFinal() == null ? "alert" : "corrected-alert:" + recipe.previousFinal();
            String profile = count
                    ? retract ? SessionAlertProposal.AGGREGATE_RETRACTION
                            : recipe.previousFinal() == null ? SessionAlertProposal.AGGREGATE : SessionAlertProposal.AGGREGATE_CORRECTION
                    : retract ? SessionAlertProposal.INTERVAL_RETRACTION
                            : recipe.previousFinal() == null ? SessionAlertProposal.INTERVAL : SessionAlertProposal.INTERVAL_CORRECTION;
            outputs.add(SessionAlertProposal.create(key, IntervalWindowSchedule.id(due.pinsDigest(), alertId),
                    output, recipe.window(), intent, input.target().id(), input.target().revision(), finalization.proof(),
                    coverageDigest, profile));
        } else if (intent != null) {
            outputs.add(SessionAlertProposal.createIntervalPrefix(key,
                    IntervalWindowSchedule.id(due.pinsDigest(), "prefix-alert:" + recipe.prefixIndex()), output,
                    recipe.window(), intent, input.target().id(), input.target().revision(), finalization.proof(),
                    coverageDigest, finalization.measuredThrough(), count));
        }
        var lease = store.acquire(key, due.pinsDigest(), holder);
        if (!lease.timers().equals(List.of(scheduled)) || !lease.snapshot().state().equals(timer.payload())) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
        var next = complete ? recipe : recipe.next(intent == null ? null : output.id());
        var timers = complete ? List.<KeyedStateStore.Timer>of() : List.of(IntervalWindowSchedule.timer(due.pinsDigest(), next));
        snapshot.reauthorize();
        var transition = new KeyedStateStore.Transition(timer.id(), scheduled.stateRevision(), timer.digest(),
                lease.snapshot().revision(), next.state(), timers,
                new KeyedStateStore.TimerInput(timer.id(), scheduled.stateRevision()), outputs);
        if (count && !idle) {
            store.commitCoveredAggregate(key, lease, transition, recipe.window().startInclusive(), active,
                    countBatch.coveredFrontier(key, recipe.window(), finalization.measuredThrough(), finalization.proof(), coverageDigest));
        } else {
            store.commitSession(key, lease, transition, recipe.window().startInclusive(), active);
        }
    }

    private static void verifyIntervalEarlyMeasure(JdbcKeyedStateStore store, KeyedStateStore.Key key,
            String pins, IntervalWindowSchedule.Recipe recipe) {
        var output = store.inspectOutput(key, pins, recipe.earlyMeasure()).output();
        var earlier = new VclParser().readJson(output.payload().payload().getBytes(StandardCharsets.UTF_8));
        if (output.kind() != KeyedStateStore.OutputKind.MEASURE
                || !"vcl-interval-final-measure-1".equals(earlier.path("profile").textValue())
                || !recipe.node().equals(earlier.path("node").textValue())
                || !recipe.window().startInclusive().toString().equals(earlier.path("windowStart").textValue())
                || !recipe.window().endExclusive().toString().equals(earlier.path("windowEnd").textValue())
                || !earlier.path("finalized").isBoolean() || earlier.path("finalized").booleanValue()
                || !"OK".equals(earlier.at("/decision/status").textValue())
                || !earlier.at("/decision/literal/value").isBoolean() || !earlier.at("/decision/literal/value").booleanValue()) {
            throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
        }
    }

    private java.util.function.BiFunction<java.time.Instant, java.time.Instant, UUID> intervalProof(
            JdbcRuleDeployments.Deployment active, ReviewedInput input, IntervalWindowSchedule.Recipe recipe,
            java.time.Instant expectedDue) {
        return (from, until) -> {
            if (!until.equals(expectedDue)) throw new VclException("INTERVAL_SCHEDULE_CHANGED", "");
            return captures.findCoverage(active.account(), active.epoch(), input.target().id(), input.target().revision(),
                    recipe.generation(), from, until);
        };
    }

    private RuleCompilationSnapshots.PreparedStart windowReferences(JdbcRuleDeployments.Deployment active, VclCompiler compiler) {
        if (checkpoints == null) throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
        return checkpoints.restoreWindowReferences(active, compiler);
    }

    void timeoutSession(JdbcRuleDeployments.Deployment candidate, JdbcKeyedStateStore store,
            KeyedStateStore.DueTimer due, VclCompiler compiler, UUID holder) {
        var active = current(candidate);
        var input = reviewedInput(active);
        if (input.session() == null) throw new VclException("SESSION_OPERATOR_REQUIRED", "");
        var head = input.start().atCurrentHead();
        long initial = VclObservationReadiness.checkpoint(head.generationStartCheckpoint());
        if (!store.sessionTimeoutReady(active, due, head.inputGeneration(), initial,
                VclObservationReadiness.checkpoint(head.capturedHeadCheckpoint()))) {
            throw new VclException("SESSION_INPUT_PENDING", "");
        }
        var snapshot = input.snapshot(input.start());
        if (input.zone()) {
            var pin = com.voltcanary.analytics.vcl.DurableZoneSession.geometryPin(due.key(), due.scheduled().timer().payload());
            snapshot.prepareZoneSession(compiler, input.session())
                    .durable(store, active, due.key(), pin.zone(), pin.revision(), circles(), () -> current(active)).timeout(due, holder);
            return;
        }
        var session = snapshot.prepareWindowSession(compiler, input.session())
                .durable(store, active, due.key(), () -> current(active));
        session.timeout(due, holder);
    }

    void finishSessionAbsence(JdbcRuleDeployments.Deployment candidate, JdbcKeyedStateStore store,
            KeyedStateStore.DueTimer due, VclCompiler compiler, UUID holder) {
        var active = current(candidate);
        var input = reviewedInput(active);
        var key = due.key();
        var saved = store.inspect(key, due.pinsDigest());
        var recipe = SessionAbsenceSchedule.recipe(due, saved.state());
        if (!recipe.node().equals(input.absence()) || saved.revision() != due.scheduled().stateRevision()) {
            throw new VclException("SESSION_WAIT_CHANGED", "");
        }
        var window = SessionAbsenceSchedule.window(key, saved.state(), input.zone());
        if (window == null) throw new VclException("SESSION_WAIT_CHANGED", "");
        var snapshot = input.snapshot(input.start());
        var pin = input.zone() ? DurableZoneSession.geometryPin(key, saved.state()) : null;
        var geometry = pin == null ? null : circles().read(active, pin.zone(), pin.revision(), snapshot.sessionScope());
        var plan = snapshot.prepareSessionAbsence(compiler, recipe.node(), null, geometry, pin == null ? null : pin.zone());
        var timer = due.scheduled().timer();
        if (!timer.dueAt().equals(plan.timing().windowCloseDueAt(window.endExclusive()))) {
            throw new VclException("SESSION_WAIT_CHANGED", "");
        }
        var boundary = active.start().boundary();
        if (active.resumeFrom() != null && active.resumeFrom().isAfter(boundary)) boundary = active.resumeFrom();
        var replayStart = plan.replayStart(window.startInclusive(), boundary);
        var coverageWindow = recipe.through() != null && recipe.through().isAfter(window.endExclusive())
                ? new VclWindowPlan.Window(window.startInclusive(), recipe.through()) : window;
        var facts = readSessionFacts(active, input, snapshot, plan, key, replayStart, coverageWindow, recipe.checkpoint());
        var kernel = new VclSessionAbsenceKernel();
        var result = evaluateSession(kernel, plan, key, saved.state(), facts, input.zone());
        long trigger = VclObservationReadiness.checkpoint(recipe.trigger());
        boolean waiting = false;
        if (replayOpen(result) || VclObservationReadiness.checkpoint(facts.cut().throughCheckpoint()) < trigger) {
            facts = snapshot.readSessionAbsenceInput(plan, key, plan.replayEnd(window.endExclusive()),
                    replayStart, coverageWindow, recipe.checkpoint(), null, true);
            result = evaluateSession(kernel, plan, key, saved.state(), facts, input.zone());
            // Speculative replay identifies a boundary but cannot finalize a decision.
            waiting = result.window() == null;
        }
        // Every retry widens the requested proof and must retain the journal observations already seen.
        for (int attempt = 0; !waiting && result.window() != null && (facts.proof() == null
                || result.window().startInclusive().isBefore(coverageWindow.startInclusive())
                || result.window().endExclusive().isAfter(coverageWindow.endExclusive())); attempt++) {
            if (attempt == 3) {
                waiting = true;
                break;
            }
            var corrected = result.window();
            coverageWindow = new VclWindowPlan.Window(
                    corrected.startInclusive().isBefore(coverageWindow.startInclusive())
                            ? corrected.startInclusive() : coverageWindow.startInclusive(),
                    corrected.endExclusive().isAfter(coverageWindow.endExclusive())
                            ? corrected.endExclusive() : coverageWindow.endExclusive());
            var expanded = readSessionFacts(active, input, snapshot, plan, key, replayStart, coverageWindow, recipe.checkpoint());
            if (expanded.proof() == null || !expanded.proof().qualifiesEventTime()
                    || VclObservationReadiness.checkpoint(expanded.cut().throughCheckpoint())
                            < Math.max(trigger, facts.lastRelevantCheckpoint())) {
                waiting = true;
                break;
            }
            facts = expanded;
            result = evaluateSession(kernel, plan, key, saved.state(), facts, input.zone());
        }
        waiting |= replayOpen(result) || "SESSION_COVERAGE_UNKNOWN".equals(result.absence().reason());
        if (waiting && !sourceIdle(store, input.start(), plan.timing())) return;
        var absence = waiting ? com.voltcanary.analytics.vcl.ScalarValue.unknown("SESSION_SOURCE_IDLE", List.of())
                : result.absence();
        var measure = JsonNodeFactory.instance.objectNode().put("profile", SessionAbsenceSchedule.MEASURE)
                .put("node", recipe.node()).put("checkpoint", facts.cut().throughCheckpoint())
                .put("closingCheckpoint", recipe.checkpoint())
                .put("status", absence.status().name()).put("reason", absence.reason());
        if (recipe.previous() != null) measure.put("supersedes", recipe.previous().toString());
        measure.set("literal", absence.literal());
        if (facts.cut().correctionRequired() != null) {
            measure.put("lateObservation", facts.cut().correctionRequired().toString());
        }
        if (result.window() != null) {
            measure.put("sessionStart", result.window().startInclusive().toString())
                    .put("sessionEnd", result.window().endExclusive().toString())
                    .put("entryObservation", result.entry().toString())
                    .put("exitObservation", result.exit().toString());
        }
        var evidence = measure.putArray("evidence");
        absence.evidence().forEach(id -> evidence.add(id.toString()));
        if (!waiting && facts.proof() != null) measure.put("proof", facts.proof().proofId().toString());
        if (result.witness() != null && result.witness().firstObservationId() != null) {
            measure.put("firstWitness", result.witness().firstObservationId().toString())
                    .put("lastWitness", result.witness().lastObservationId().toString())
                    .put("coveredDurationUs", Long.toString(result.witness().coveredDurationUs()));
        }
        if (!result.positionWitnesses().isEmpty()) {
            var positions = measure.putArray("positionWitnesses");
            result.positionWitnesses().forEach(id -> positions.add(id.toString()));
        }
        var coverage = waiting ? null : facts.proof();
        String coverageDigest = coverage != null && coverage.qualifiesEventTime() ? coverage.eventTimeDigest() : null;
        if (coverageDigest != null) measure.put("coverageDigest", coverageDigest);
        String purpose = recipe.previous() == null ? "measure" : "measure:" + recipe.trigger();
        var output = new KeyedStateStore.Output(SessionAbsenceSchedule.id(due.pinsDigest(), purpose),
                KeyedStateStore.OutputKind.MEASURE, null, KeyedStateStore.State.parse(VclCanonical.encode(measure)));
        var outputs = new java.util.ArrayList<KeyedStateStore.Output>();
        outputs.add(output);
        var verdict = absence;
        var resultWindow = result.window();
        boolean known = verdict.status() == com.voltcanary.analytics.vcl.ScalarValue.Status.OK;
        boolean retraction = known && recipe.previous() != null && result.witness() != null
                && result.witness().firstObservationId() != null && !verdict.literal().path("value").asBoolean();
        String effectProfile = retraction ? SessionAlertProposal.RETRACTION
                : recipe.previous() == null ? SessionAlertProposal.PROFILE : SessionAlertProposal.CORRECTION;
        if (resultWindow != null && coverageDigest != null
                && known
                && (verdict.literal().path("value").booleanValue() || retraction)) {
            snapshot.sessionAlertIntent(recipe.node(), active.bundleDigest()).ifPresent(intent ->
                    outputs.add(SessionAlertProposal.create(key, SessionAbsenceSchedule.id(due.pinsDigest(), "alert:" + purpose),
                            output, resultWindow, intent, input.target().id(), input.target().revision(),
                            coverage, coverageDigest, effectProfile)));
        }
        snapshot.reauthorize();
        var lease = store.acquire(key, due.pinsDigest(), holder);
        if (!lease.snapshot().equals(saved) || !lease.timers().equals(List.of(due.scheduled()))) {
            throw new VclException("SESSION_WAIT_CHANGED", "");
        }
        store.commitSession(key, lease, new KeyedStateStore.Transition(timer.id(), saved.revision(), timer.digest(),
                saved.revision(), saved.state(), List.of(), new KeyedStateStore.TimerInput(timer.id(), saved.revision()),
                outputs), recipe.openedAt(), active);
    }

    private static boolean sourceIdle(JdbcKeyedStateStore store, SourceCaptureSnapshot journal,
            com.voltcanary.analytics.vcl.VclTimingPlan timing) {
        var head = journal.atCurrentHead();
        var last = head.lastRecordedAt();
        var now = store.databaseTime().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return !now.isBefore(last) && timing.idleExpired(last, now)
                && head.capturedHeadCheckpoint().equals(journal.atCurrentHead().capturedHeadCheckpoint());
    }

    private RuleCompilationSnapshot.SessionAbsenceInput readSessionFacts(JdbcRuleDeployments.Deployment active,
            ReviewedInput input, RuleCompilationSnapshot snapshot, VclSessionAbsenceKernel.Plan plan,
            KeyedStateStore.Key key, java.time.Instant replayStart,
            VclWindowPlan.Window coverageWindow, String closingCheckpoint) {
        UUID proof = captures.findCoverage(active.account(), active.epoch(), input.target().id(), input.target().revision(),
                input.start().inputGeneration(), coverageWindow.startInclusive(),
                plan.timing().windowCloseDueAt(coverageWindow.endExclusive()));
        return snapshot.readSessionAbsenceInput(plan, key, coverageWindow.endExclusive(), replayStart, coverageWindow,
                closingCheckpoint, proof, false);
    }

    private static boolean replayOpen(VclSessionAbsenceKernel.Result result) {
        return "SESSION_REPLAY_OPEN".equals(result.absence().reason());
    }

    private static VclSessionAbsenceKernel.Result evaluateSession(VclSessionAbsenceKernel kernel,
            VclSessionAbsenceKernel.Plan plan, KeyedStateStore.Key key, KeyedStateStore.State state,
            RuleCompilationSnapshot.SessionAbsenceInput facts, boolean zone) {
        return zone
                ? kernel.evaluate(plan, DurableZoneSession.sessionState(key, state), facts.cut(), facts.proof(), plan.scope())
                : kernel.evaluate(plan, DurableWindowSession.decode(key, state), facts.cut(), facts.proof(), plan.scope());
    }

    record SustainedFinalization(VclWindowPlan.Window window, VclSourceCoverage proof, String inputDigest,
            boolean confirmed, boolean retraction, UUID binding, long revision, DurableJournalWindow.AlertIntent intent) { }

    void finishSustained(JdbcRuleDeployments.Deployment candidate, JdbcKeyedStateStore store,
            JdbcKeyedStateStore.PendingSustained pending, VclCompiler compiler, UUID holder) {
        var key = pending.key();
        var source = store.inspectOutput(key, pending.pins(), pending.measure());
        if (source.receiptId() != null) return;
        var finalization = finalizeSustained(candidate, store, key, source.output(), compiler);
        if (finalization.isEmpty()) return;
        var result = finalization.get();
        var original = new VclParser().readJson(source.output().payload().payload().getBytes(StandardCharsets.UTF_8));
        String occurrence = original.path("occurrence").textValue();
        boolean idle = result.proof() == null;
        String coverageDigest = idle ? null : result.proof().eventTimeDigest();
        var measure = JsonNodeFactory.instance.objectNode().put("profile", "vcl-sustained-final-measure-1")
                .put("sourceMeasure", source.output().id().toString()).put("occurrence", occurrence)
                .put("sessionStart", result.window().startInclusive().toString())
                .put("sessionEnd", result.window().endExclusive().toString())
                .put("status", result.confirmed() ? "OK" : "UNKNOWN");
        if (!idle) {
            measure.put("inputDigest", result.inputDigest()).put("coverageDigest", coverageDigest)
                    .put("proof", result.proof().proofId().toString());
        }
        if (result.confirmed()) measure.putObject("literal").put("type", "BOOLEAN").put("value", !result.retraction());
        else measure.putNull("literal").put("reason", idle ? "SUSTAINED_SOURCE_IDLE" : "SUSTAINED_WITNESS_CHANGED");
        if (original.path("correction").isTextual()) measure.set("correction", original.path("correction"));
        UUID measureId = SessionAbsenceSchedule.id(pending.pins(), "sustained-final:" + pending.measure());
        var output = new KeyedStateStore.Output(measureId, KeyedStateStore.OutputKind.MEASURE, null,
                KeyedStateStore.State.parse(VclCanonical.encode(measure)));
        var outputs = new java.util.ArrayList<KeyedStateStore.Output>();
        outputs.add(output);
        if (result.confirmed() && result.intent() != null) {
            String profile = result.retraction() ? SessionAlertProposal.SUSTAINED_RETRACTION
                    : original.path("correction").isTextual() ? SessionAlertProposal.SUSTAINED_CORRECTION : SessionAlertProposal.SUSTAINED;
            outputs.add(SessionAlertProposal.create(key, occurrence,
                    SessionAbsenceSchedule.id(pending.pins(), "sustained-alert:" + pending.measure()), output,
                    result.window(), result.intent(), result.binding(), result.revision(), result.proof(), coverageDigest,
                    profile));
        }
        var active = current(candidate);
        var lease = store.acquire(key, pending.pins(), holder);
        var receipt = new KeyedStateStore.Acknowledgement(pending.measure(), source.output().digest(), measureId, output.digest());
        store.commitSustainedFinalization(key, lease, new KeyedStateStore.Transition(measureId, 1,
                source.output().digest(), lease.snapshot().revision(), lease.snapshot().state(),
                lease.timers().stream().map(KeyedStateStore.ScheduledTimer::timer).toList(), null, outputs),
                result.window().startInclusive(), active, receipt);
    }

    java.util.Optional<SustainedFinalization> finalizeSustained(JdbcRuleDeployments.Deployment candidate,
            JdbcKeyedStateStore store, KeyedStateStore.Key key, KeyedStateStore.Output measure, VclCompiler compiler) {
        var active = current(candidate);
        var input = reviewedInput(active);
        if (input.sustained() == null || measure.kind() != KeyedStateStore.OutputKind.MEASURE
                || !active.account().equals(key.accountId()) || !active.rule().equals(key.ruleId())
                || !active.epoch().equals(key.deploymentEpoch())) throw new VclException("WORKER_TARGET_INVALID", "");
        var value = new VclParser().readJson(measure.payload().payload().getBytes(StandardCharsets.UTF_8));
        var generation = input.start().inputGeneration();
        if (!DurableSustained.PROFILE.equals(value.path("profile").textValue())
                || !generation.toString().equals(value.path("generation").textValue())) {
            throw new VclException("SUSTAINED_MEASURE_CHANGED", "");
        }
        var window = new VclWindowPlan.Window(java.time.Instant.parse(value.path("startedAt").textValue()),
                java.time.Instant.parse(value.path("qualifiedAt").textValue()));
        boolean retraction = value.path("replayFrom").isTextual();
        var replay = retraction ? new VclWindowPlan.Window(java.time.Instant.parse(value.path("replayFrom").textValue()),
                java.time.Instant.parse(value.path("replayThrough").textValue())) : window;
        if (!active.includesWindow(replay.startInclusive()) || !active.includesWindow(window.startInclusive())
                || replay.startInclusive().isAfter(window.startInclusive()) || replay.endExclusive().isBefore(window.endExclusive())) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        var journal = input.start().atCheckpoint(value.path("checkpoint").textValue());
        var snapshot = input.snapshot(journal);
        var plan = snapshot.prepareSustained(compiler, input.sustained());
        var timing = plan.timing();
        var due = timing.windowCloseDueAt(replay.endExclusive());
        if (store.databaseTime().isBefore(due)) return java.util.Optional.empty();
        UUID proofId = captures.findCoverage(active.account(), active.epoch(), plan.binding(), plan.schemaRevision(),
                generation, replay.startInclusive(), due);
        var proof = proofId == null ? null : journal.coverage(proofId, replay.startInclusive(), due, true);
        if (proof == null || !proof.qualifiesEventTime()) {
            return idleSustained(store, journal, timing, window, plan.binding(), plan.schemaRevision(), retraction);
        }
        var dependency = timing.requiredInputs().stream()
                .filter(dep -> dep.kind() == com.voltcanary.vcl.VclMetadata.Resource.BINDING
                        && plan.binding().equals(dep.id()) && dep.revision() == plan.schemaRevision())
                .findFirst().orElseThrow(() -> new VclException("SUSTAINED_SOURCE_CHANGED", ""));
        var frontier = timing.watermark(List.of(new com.voltcanary.analytics.vcl.VclTimingPlan.Progress(
                dependency, proof.evidence().until(), proof.acceptedAt())), timing.digest(), snapshot.sessionScope());
        if (frontier.status() != com.voltcanary.analytics.vcl.ScalarValue.Status.OK
                || java.time.Instant.parse(frontier.literal().path("value").textValue()).isBefore(replay.endExclusive())) {
            return idleSustained(store, journal, timing, window, plan.binding(), plan.schemaRevision(), retraction);
        }
        var finalCut = journal.forFinalization(proof);
        var preview = input.snapshot(finalCut).previewSustained(compiler, input.sustained(), key, replay,
                RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES);
        if (preview.result().value().isFailure()) throw new VclException(preview.result().value().reason(), "");
        boolean confirmed = retraction ? preview.result().unknownEvidence().isEmpty()
                && preview.episodes().stream().noneMatch(episode -> episode.occurrenceDigest().equals(value.path("occurrence").textValue()))
                : preview.episodes().stream().anyMatch(episode ->
                episode.occurrenceDigest().equals(value.path("occurrence").textValue())
                        && episode.firstObservation().toString().equals(value.path("firstObservation").textValue())
                        && episode.qualifyingObservation().toString().equals(value.path("qualifyingObservation").textValue())
                        && Long.toString(episode.coveredDurationUs()).equals(value.path("coveredDurationUs").textValue()));
        if (!proof.equals(finalCut.coverage(proofId, replay.startInclusive(), due))) {
            throw new VclException("SUSTAINED_COVERAGE_CHANGED", "");
        }
        current(active);
        var intent = snapshot.sessionAlertIntent(input.sustained(), active.bundleDigest()).orElse(null);
        return java.util.Optional.of(new SustainedFinalization(window, proof, preview.inputDigest(), confirmed, retraction,
                plan.binding(), plan.schemaRevision(), intent));
    }

    private static java.util.Optional<SustainedFinalization> idleSustained(JdbcKeyedStateStore store,
            SourceCaptureSnapshot journal, com.voltcanary.analytics.vcl.VclTimingPlan timing,
            VclWindowPlan.Window window, UUID binding, long revision, boolean retraction) {
        return sourceIdle(store, journal, timing)
                ? java.util.Optional.of(new SustainedFinalization(window, null, null, false, retraction, binding, revision, null))
                : java.util.Optional.empty();
    }

    private record ReviewedInput(VclDocument document, SourceBindingSnapshots.Target target, SourceCaptureSnapshot start,
            AutomationGrantSnapshot grant, EvidencePolicySnapshot policy, String aggregate, String session,
            boolean zone, String absence, String sustained, String interval, boolean references) {
        RuleCompilationSnapshot snapshot(SourceCaptureSnapshot journal) {
            return new RuleCompilationSnapshot(document, null, null, grant, policy,
                    List.of(target), List.of(), Map.of(target, journal));
        }
    }

    private ReviewedInput reviewedInput(JdbcRuleDeployments.Deployment active) {
        if (active.start() == null) throw new VclException("START_CHECKPOINT_UNAVAILABLE", "");
        var bundle = bundles.find(active.account(), active.bundle())
                .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest())) throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var parser = new VclParser();
        var compiled = parser.readJson(bundle.payload().getBytes(StandardCharsets.UTF_8)).path("compilation");
        var document = parser.parse(compiled.path("canonicalDocument").textValue());
        var inputs = deployments.readStartRecipe(active.account(), active.epoch()).inputs();
        if (!active.start().checkpointDigest().equals(inputs.checkpointDigest()) || inputs.cuts().size() != 1
                || !active.start().boundary().equals(inputs.boundary())
                || !inputs.documentDigest().equals(compiled.path("documentDigest").textValue())) {
            throw new VclException("START_CHECKPOINT_CHANGED", "");
        }
        var target = inputs.cuts().keySet().iterator().next();
        UUID policy = null;
        String aggregate = null;
        String session = null;
        String absence = null;
        String sustained = null;
        String interval = null;
        boolean zone = false;
        for (var node : compiled.path("graph")) {
            if ("emit_alert".equals(node.path("method").textValue())) policy = uuid(node.at("/config/evidencePolicyId").textValue());
            if ("window_aggregate".equals(node.path("method").textValue())) aggregate = node.path("id").textValue();
            if ("interval_union_window".equals(node.path("method").textValue())) {
                if (interval != null) throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
                interval = node.path("id").textValue();
            }
            if ("sustained".equals(node.path("method").textValue())) {
                if (sustained != null) throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
                sustained = node.path("id").textValue();
            }
            if ("absent_within".equals(node.path("method").textValue())) {
                if (absence != null) throw new VclException("SESSION_PROFILE_UNAVAILABLE", "");
                absence = node.path("id").textValue();
            }
            if (java.util.Set.of("window_session", "zone_session").contains(node.path("method").textValue())) {
                if (session != null) throw new VclException("SESSION_PROFILE_UNAVAILABLE", "");
                session = node.path("id").textValue();
                zone = "zone_session".equals(node.path("method").textValue());
            }
        }
        int profiles = (aggregate == null ? 0 : 1) + (session == null ? 0 : 1) + (sustained == null ? 0 : 1)
                + (interval == null ? 0 : 1);
        if (policy == null || profiles != 1 || interval == null && aggregate == null && !inputs.references().isEmpty()) {
            throw new VclException("WINDOW_GRAPH_UNSUPPORTED", "");
        }
        var start = captures.capture(active.account(), active.epoch(), target.id(), target.revision(), inputs.cuts().get(target));
        UUID policyId = policy;
        var grant = new AutomationGrantSnapshot(grants.read(active.account(), active.epoch()),
                () -> grants.read(active.account(), active.epoch()));
        var evidence = new EvidencePolicySnapshot(policies.read(active.account(), active.epoch(), policyId),
                () -> policies.read(active.account(), active.epoch(), policyId));
        return new ReviewedInput(document, target, start, grant, evidence, aggregate, session, zone, absence, sustained,
                interval, !inputs.references().isEmpty());
    }

    private WorkerCircleReads circles() {
        if (circles == null) throw new VclException("WORKER_SPATIAL_OWNER_UNAVAILABLE", "");
        return circles;
    }

    public DurableJournalWindow restoreWindow(JdbcRuleDeployments.Deployment candidate, KeyedStateStore store,
            KeyedStateStore.Key key, String pins, VclCompiler compiler) {
        if (candidate == null || store == null || key == null || pins == null || compiler == null
                || !candidate.account().equals(key.accountId()) || !candidate.rule().equals(key.ruleId())
                || !candidate.epoch().equals(key.deploymentEpoch())) {
            throw new VclException("WORKER_TARGET_INVALID", "");
        }
        var active = current(candidate);
        var bundle = bundles.find(active.account(), active.bundle())
                .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest())) throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var root = new VclParser().readJson(bundle.payload().getBytes(StandardCharsets.UTF_8));
        var compiled = root.path("compilation");
        var recipe = DurableJournalWindow.recipe(store.inspect(key, pins).state());
        VclDocument document = new VclParser().parse(recipe.document());
        if (document.kind() != VclDocument.Kind.RULE
                || !recipe.document().equals(compiled.path("canonicalDocument").textValue())
                || !active.rule().toString().equals(document.spec().path("ruleId").textValue())
                || !active.grant().toString().equals(document.spec().path("executionGrantId").textValue())
                || !active.includesWindow(recipe.window().startInclusive())
                || recipe.membershipBudget() <= 0) {
            throw new VclException("WINDOW_RECIPE_INVALID", "");
        }
        UUID policy = null;
        int sources = 0;
        for (var node : compiled.path("graph")) {
            switch (node.path("method").textValue()) {
                case "read_stream" -> {
                    sources++;
                    if (!recipe.binding().toString().equals(node.at("/config/bindingId").textValue())
                            || recipe.schemaRevision() != node.at("/config/schemaRevision").longValue()) {
                        throw new VclException("WINDOW_RECIPE_INVALID", "");
                    }
                }
                case "lookup_asof" -> throw new VclException("ASOF_SNAPSHOT_REQUIRED", "");
                case "emit_alert" -> {
                    var id = uuid(node.at("/config/evidencePolicyId").textValue());
                    if (policy != null && !policy.equals(id)) throw new VclException("WORKER_POLICY_NOT_PINNED", "");
                    policy = id;
                }
                default -> { }
            }
        }
        if (sources != 1 || policy == null) throw new VclException("WINDOW_RECIPE_INVALID", "");
        var grant = new AutomationGrantSnapshot(grants.read(active.account(), active.epoch()),
                () -> grants.read(active.account(), active.epoch()));
        UUID policyId = policy;
        var evidence = new EvidencePolicySnapshot(policies.read(active.account(), active.epoch(), policyId),
                () -> policies.read(active.account(), active.epoch(), policyId));
        var target = new SourceBindingSnapshots.Target(recipe.binding(), recipe.schemaRevision());
        var journal = captures.capture(active.account(), active.epoch(), recipe.binding(), recipe.schemaRevision(), recipe.cut());
        var snapshot = new RuleCompilationSnapshot(document, null, null, grant, evidence,
                List.of(target), List.of(), Map.of(target, journal));
        var batch = snapshot.prepareJournalWindowBatch(compiler, recipe.nodeId(), recipe.membershipBudget(), active.start() != null);
        if (!(store instanceof JdbcKeyedStateStore jdbc)) {
            throw new VclException("WORKER_TRANSACTION_UNAVAILABLE", "");
        }
        var restored = batch.durable(store, active.epoch(), recipe.subject(), recipe.window(), recipe.coverageProof(),
                () -> current(active), (targetKey, lease, transition, watermark) -> jdbc.commitJournalWindow(
                        targetKey, lease, transition, recipe.window().startInclusive(), active, watermark),
                active.start() == null ? null : active.bundleDigest(),
                () -> captures.findCoverage(active.account(), active.epoch(), recipe.binding(), recipe.schemaRevision(),
                        journal.inputGeneration(), recipe.window().startInclusive(), batch.closeDueAt(recipe.window())));
        if (!key.equals(restored.key()) || !pins.equals(restored.pinsDigest())) throw new VclException("KEY_PINS_CHANGED", "");
        current(active);
        return restored;
    }

    private JdbcRuleDeployments.Deployment current(JdbcRuleDeployments.Deployment expected) {
        var current = deployments.find(expected.account(), expected.epoch())
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!expected.equals(current) || !current.acceptsExistingWork()) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        return current;
    }

    private static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException invalid) { throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", ""); }
    }
}
