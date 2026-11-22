package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.VclSessionAbsenceKernel;
import com.voltcanary.analytics.vcl.VclSustainedPlan;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceCoverage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Rebuilds one direct-journal diagnostic window from its durable recipe under machine authority.
 */
public final class WorkerRuleCompilationSnapshots {
    private final JdbcCompilationBundleStore bundles;
    private final JdbcRuleDeployments deployments;
    final WorkerSourceCaptureSnapshots captures;
    private final WorkerGrantReads grants;
    private final WorkerPolicyReads policies;
    final WorkerCircleReads circles;
    final WorkerStartCheckpoints checkpoints;

    public WorkerRuleCompilationSnapshots(
            DataSource source,
            WorkerSourceCaptureSnapshots captures,
            WorkerGrantReads grants,
            WorkerPolicyReads policies) {
        this(source, captures, grants, policies, null, null);
    }

    WorkerRuleCompilationSnapshots(
            DataSource source,
            WorkerSourceCaptureSnapshots captures,
            WorkerGrantReads grants,
            WorkerPolicyReads policies,
            WorkerCircleReads circles,
            WorkerStartCheckpoints checkpoints) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        this.captures = captures;
        this.grants = grants;
        this.policies = policies;
        this.circles = circles;
        this.checkpoints = checkpoints;
    }

    WorkerRuleCompilationSnapshots(
            JdbcCompilationBundleStore bundles,
            JdbcRuleDeployments deployments,
            WorkerSourceCaptureSnapshots captures,
            WorkerGrantReads grants,
            WorkerPolicyReads policies) {
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

    void openPage(
            JdbcRuleDeployments.Deployment candidate,
            JdbcKeyedStateStore store,
            VclCompiler compiler,
            UUID holder) {
        var active = current(candidate);
        if (active.start() == null) return;
        var input = reviewedInput(active);
        boolean draining = active.state() == JdbcRuleDeployments.State.DRAINING;
        if (draining && input.session() == null) return;
        var latest = input.start().atCurrentHead();
        UUID generation = latest.inputGeneration();
        long initial =
                com.voltcanary.vcl.VclObservationReadiness.checkpoint(
                        latest.generationStartCheckpoint());
        long after = store.openingCheckpoint(active, generation, initial);
        long head =
                com.voltcanary.vcl.VclObservationReadiness.checkpoint(
                        latest.capturedHeadCheckpoint());
        if (after > head) throw new VclException("CAPTURE_CHECKPOINT_REGRESSED", "");
        if (after == head) return;
        Long referenceRevision =
                input.references() ? store.referenceCorrectionRevision(active) : null;
        var page = latest.readPage(Long.toString(after), 8);
        var plans = prepareOpeningPlans(active, input, compiler);
        for (var observation : page.observations()) {
            String checkpoint = observation.path("checkpoint").textValue();
            long next = VclObservationReadiness.checkpoint(checkpoint);
            openObservation(
                    active,
                    input,
                    latest,
                    observation,
                    checkpoint,
                    next,
                    store,
                    plans,
                    compiler,
                    holder,
                    draining);
            if (!store.advanceOpeningCheckpoint(
                    active, generation, initial, after, next, referenceRevision)) return;
            after = next;
        }
    }

    private record OpeningPlans(
            RuleCompilationSnapshot snapshot,
            RulePreparedLookups references,
            RuleZoneBatch zone,
            RuleSessionBatch session,
            VclSustainedPlan sustained,
            RuleJournalWindowBatch aggregate) {}

    OpeningPlans prepareOpeningPlans(
            JdbcRuleDeployments.Deployment active, ReviewedInput input, VclCompiler compiler) {
        var snapshot = input.snapshot(input.start());
        var references = input.references() ? windowReferences(active, compiler).lookups() : null;
        var zone = input.zone() ? snapshot.prepareZoneSession(compiler, input.session()) : null;
        var session =
                input.session() != null && !input.zone()
                        ? snapshot.prepareWindowSession(compiler, input.session())
                        : null;
        var sustained =
                input.sustained() == null
                        ? null
                        : snapshot.prepareSustained(compiler, input.sustained());
        var aggregate =
                input.aggregate() == null
                        ? null
                        : references != null
                                ? references.journalAggregate(input.aggregate())
                                : snapshot.prepareJournalWindowBatch(
                                        compiler,
                                        input.aggregate(),
                                        64L
                                                * com.voltcanary.analytics.vcl
                                                        .VclWindowAggregateKernel.MAX_INPUTS,
                                        true);
        return new OpeningPlans(snapshot, references, zone, session, sustained, aggregate);
    }

    void openObservation(
            JdbcRuleDeployments.Deployment active,
            ReviewedInput input,
            SourceCaptureSnapshot latest,
            JsonNode observation,
            String checkpoint,
            long next,
            JdbcKeyedStateStore store,
            OpeningPlans plans,
            VclCompiler compiler,
            UUID holder,
            boolean draining) {
        var snapshot = plans.snapshot();
        var references = plans.references();
        if (input.session() != null) {
            if (input.zone()) {
                var session = plans.zone();
                var routed = session.route(active.epoch(), observation);
                if (routed.isEmpty() && input.absence() != null) {
                    session.unresolved(observation)
                            .ifPresent(
                                    value ->
                                            reconsiderSessions(
                                                    active,
                                                    input,
                                                    snapshot,
                                                    store,
                                                    holder,
                                                    observation,
                                                    checkpoint,
                                                    value));
                }
                routed.filter(
                                candidate ->
                                        active.includesSessionObservation(
                                                candidate.value().eventTime()))
                        .ifPresent(
                                candidate -> {
                                    var value = candidate.value();
                                    var pinned = store.zonePin(value.key());
                                    if (pinned.isEmpty()
                                            && (draining
                                                    || !candidate.included()
                                                    || value.position() == null
                                                    || store.drainingOccurrence(
                                                            active, value.key()))) return;
                                    UUID zone =
                                            pinned.map(
                                                            com.voltcanary.analytics.vcl
                                                                            .DurableZoneSession
                                                                            .GeometryPin
                                                                    ::zone)
                                                    .orElse(value.zone());
                                    if (zone != null)
                                        session.durable(
                                                        store,
                                                        active,
                                                        value.key(),
                                                        zone,
                                                        pinned.map(
                                                                        com.voltcanary.analytics.vcl
                                                                                        .DurableZoneSession
                                                                                        .GeometryPin
                                                                                ::revision)
                                                                .orElse(0L),
                                                        circles(),
                                                        () -> current(active),
                                                        input.absence(),
                                                        checkpoint,
                                                        value.eventTime())
                                                .observe(
                                                        value,
                                                        holder,
                                                        (from, through) ->
                                                                session.replayHistory(
                                                                        active,
                                                                        value.key(),
                                                                        checkpoint,
                                                                        from,
                                                                        through),
                                                        candidate.included());
                                });
            } else {
                var session = plans.session();
                var routed = session.route(active.epoch(), observation);
                if (routed.isEmpty() && input.absence() != null) {
                    session.unresolved(observation)
                            .ifPresent(
                                    value ->
                                            reconsiderSessions(
                                                    active,
                                                    input,
                                                    snapshot,
                                                    store,
                                                    holder,
                                                    observation,
                                                    checkpoint,
                                                    value));
                }
                routed.filter(
                                value ->
                                        active.includesSessionObservation(value.value().eventTime())
                                                && (draining || !value.included()
                                                        ? store.hasCommittedState(value.key())
                                                        : !store.drainingOccurrence(
                                                                active, value.key())))
                        .ifPresent(
                                value ->
                                        session.durable(
                                                        store,
                                                        active,
                                                        value.key(),
                                                        () -> current(active),
                                                        input.absence(),
                                                        checkpoint,
                                                        value.value().eventTime())
                                                .observe(
                                                        value.value(),
                                                        holder,
                                                        (from, through) ->
                                                                session.replayHistory(
                                                                        active,
                                                                        value.key(),
                                                                        checkpoint,
                                                                        from,
                                                                        through),
                                                        value.included()));
            }
            snapshot.reauthorize();
        } else if (input.interval() != null) {
            if (references != null) {
                references.stageIntervalWindows(
                        input.interval(), input.target(), active, store, observation, holder);
            } else {
                snapshot.stageIntervalWindows(
                        compiler,
                        input.interval(),
                        input.target(),
                        active,
                        store,
                        observation,
                        holder);
                snapshot.reauthorize();
            }
        } else if (input.sustained() != null) {
            snapshot.observeSustained(
                    plans.sustained(),
                    input.sustained(),
                    active,
                    store,
                    observation,
                    holder,
                    () -> current(active));
            snapshot.reauthorize();
        } else {
            var batch = plans.aggregate();
            var route = batch.route(observation);
            var windows =
                    route.windows().stream()
                            .filter(window -> active.includesWindow(window.startInclusive()))
                            .toList();
            if (route.subject() == null) {
                var occurrences = windows.stream().map(IntervalWindowSchedule::occurrence).toList();
                for (var window : store.registeredWindows(active, occurrences)) {
                    snapshot.reauthorize();
                    IntervalWindowSchedule.stageCorrected(store, active, window, next, holder);
                }
            } else {
                for (var window : windows) {
                    batch.stageAggregateWindow(
                            store, active, route.subject(), window, next, observation, holder);
                }
            }
            snapshot.reauthorize();
        }
        if (references != null) {
            var timing =
                    com.voltcanary.analytics.vcl.VclTimingPlan.compile(references.compilation());
            var eventTime = java.time.Instant.parse(observation.path("eventTime").textValue());
            var recordedAt = java.time.Instant.parse(observation.path("recordedAt").textValue());
            if (timing.classify(eventTime, recordedAt)
                    != com.voltcanary.analytics.vcl.VclTimingPlan.Admission.QUARANTINED_FUTURE) {
                var range =
                        RuleCompilationSnapshot.correctionRange(
                                observation, latest, input.target(), input.interval() != null);
                for (var corrected :
                        store.correctedWindows(
                                active, range.startInclusive(), range.endExclusive())) {
                    snapshot.reauthorize();
                    IntervalWindowSchedule.stageCorrected(store, active, corrected, next, holder);
                }
            }
        }
    }

    void reconsiderSessions(
            JdbcRuleDeployments.Deployment active,
            ReviewedInput input,
            RuleCompilationSnapshot snapshot,
            JdbcKeyedStateStore store,
            UUID holder,
            JsonNode observation,
            String checkpoint,
            RuleCompilationSnapshot.UnresolvedSessionInput unresolved) {
        if (!active.includesSessionObservation(unresolved.at())) return;
        UUID event = uuid(observation.path("inputEventId").textValue());
        String digest =
                VclCanonical.sha256(
                        "UNRESOLVED_SESSION_INPUT/1\n" + VclCanonical.encode(observation));
        for (var candidate : store.sessionCandidates(active, unresolved)) {
            var key = candidate.key();
            var saved = store.inspect(key, candidate.pins());
            var window = SessionAbsenceSchedule.window(key, saved.state(), input.zone());
            if (window == null
                    || unresolved.at().isBefore(window.startInclusive())
                    || unresolved.at().isAfter(window.endExclusive())) continue;
            var prior =
                    store.inputReceipt(
                            key, candidate.pins(), KeyedStateStore.InputKind.OBSERVATION, event, 1);
            if (prior.isPresent()) {
                if (!digest.equals(prior.get().inputDigest()))
                    throw new VclException("KEY_INPUT_CONFLICT", "");
                continue;
            }
            var lease = store.acquire(key, candidate.pins(), holder);
            if (!lease.snapshot().equals(saved)) throw new VclException("SESSION_WAIT_CHANGED", "");
            var unchanged =
                    new KeyedStateStore.Transition(
                            event, 1, digest, saved.revision(), saved.state(), List.of(), null);
            var scheduled =
                    SessionAbsenceSchedule.schedule(
                            store,
                            key,
                            lease,
                            unchanged,
                            input.absence(),
                            checkpoint,
                            candidate.openedAt(),
                            unresolved.at(),
                            input.zone(),
                            unresolved.timing());
            if (scheduled
                    .timers()
                    .equals(
                            lease.timers().stream()
                                    .map(KeyedStateStore.ScheduledTimer::timer)
                                    .toList())) continue;
            snapshot.reauthorize();
            store.commitSession(key, lease, scheduled, candidate.openedAt(), active);
        }
    }

    void finishIntervalWindow(
            JdbcRuleDeployments.Deployment candidate,
            JdbcKeyedStateStore store,
            KeyedStateStore.DueTimer due,
            VclCompiler compiler,
            UUID holder) {
        new WorkerIntervalFinalization(this)
                .finishIntervalWindow(candidate, store, due, compiler, holder);
    }

    private static void verifyIntervalEarlyMeasure(
            JdbcKeyedStateStore store,
            KeyedStateStore.Key key,
            String pins,
            IntervalWindowSchedule.Recipe recipe) {
        WorkerIntervalFinalization.verifyIntervalEarlyMeasure(store, key, pins, recipe);
    }

    private java.util.function.BiFunction<java.time.Instant, java.time.Instant, UUID> intervalProof(
            JdbcRuleDeployments.Deployment active,
            ReviewedInput input,
            IntervalWindowSchedule.Recipe recipe,
            java.time.Instant expectedDue) {
        return new WorkerIntervalFinalization(this)
                .intervalProof(active, input, recipe, expectedDue);
    }

    RuleCompilationSnapshots.PreparedStart windowReferences(
            JdbcRuleDeployments.Deployment active, VclCompiler compiler) {
        if (checkpoints == null) throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
        return checkpoints.restoreWindowReferences(active, compiler);
    }

    void timeoutSession(
            JdbcRuleDeployments.Deployment candidate,
            JdbcKeyedStateStore store,
            KeyedStateStore.DueTimer due,
            VclCompiler compiler,
            UUID holder) {
        new WorkerSessionFinalization(this).timeoutSession(candidate, store, due, compiler, holder);
    }

    void finishSessionAbsence(
            JdbcRuleDeployments.Deployment candidate,
            JdbcKeyedStateStore store,
            KeyedStateStore.DueTimer due,
            VclCompiler compiler,
            UUID holder) {
        new WorkerSessionFinalization(this)
                .finishSessionAbsence(candidate, store, due, compiler, holder);
    }

    private static UUID sessionAlertBasis(
            JdbcKeyedStateStore store,
            KeyedStateStore.DueTimer due,
            SessionAbsenceSchedule.Recipe recipe) {
        return WorkerSessionFinalization.sessionAlertBasis(store, due, recipe);
    }

    static boolean sourceIdle(
            JdbcKeyedStateStore store,
            SourceCaptureSnapshot journal,
            com.voltcanary.analytics.vcl.VclTimingPlan timing) {
        return WorkerSessionFinalization.sourceIdle(store, journal, timing);
    }

    private RuleCompilationSnapshot.SessionAbsenceInput readSessionFacts(
            JdbcRuleDeployments.Deployment active,
            ReviewedInput input,
            RuleCompilationSnapshot snapshot,
            VclSessionAbsenceKernel.Plan plan,
            KeyedStateStore.Key key,
            java.time.Instant replayStart,
            VclWindowPlan.Window coverageWindow,
            String closingCheckpoint,
            long minimumCheckpoint) {
        return new WorkerSessionFinalization(this)
                .readSessionFacts(
                        active,
                        input,
                        snapshot,
                        plan,
                        key,
                        replayStart,
                        coverageWindow,
                        closingCheckpoint,
                        minimumCheckpoint);
    }

    private static boolean replayOpen(VclSessionAbsenceKernel.Result result) {
        return WorkerSessionFinalization.replayOpen(result);
    }

    private static VclSessionAbsenceKernel.Result evaluateSession(
            VclSessionAbsenceKernel kernel,
            VclSessionAbsenceKernel.Plan plan,
            KeyedStateStore.Key key,
            KeyedStateStore.State state,
            RuleCompilationSnapshot.SessionAbsenceInput facts,
            boolean zone) {
        return WorkerSessionFinalization.evaluateSession(kernel, plan, key, state, facts, zone);
    }

    static void recheckCoverage(
            SourceCaptureSnapshot journal, VclSourceCoverage proof, String error) {
        WorkerSessionFinalization.recheckCoverage(journal, proof, error);
    }

    record SustainedFinalization(
            VclWindowPlan.Window window,
            VclSourceCoverage proof,
            String inputDigest,
            boolean confirmed,
            boolean retraction,
            UUID binding,
            long revision,
            DurableJournalWindow.AlertIntent intent,
            SourceCaptureSnapshot journal) {}

    void finishSustained(
            JdbcRuleDeployments.Deployment candidate,
            JdbcKeyedStateStore store,
            JdbcKeyedStateStore.PendingSustained pending,
            VclCompiler compiler,
            UUID holder) {
        new WorkerSustainedFinalization(this)
                .finishSustained(candidate, store, pending, compiler, holder);
    }

    java.util.Optional<SustainedFinalization> finalizeSustained(
            JdbcRuleDeployments.Deployment candidate,
            JdbcKeyedStateStore store,
            KeyedStateStore.Key key,
            KeyedStateStore.Output measure,
            VclCompiler compiler) {
        return new WorkerSustainedFinalization(this)
                .finalizeSustained(candidate, store, key, measure, compiler);
    }

    static java.util.Optional<SustainedFinalization> idleSustained(
            JdbcKeyedStateStore store,
            SourceCaptureSnapshot journal,
            com.voltcanary.analytics.vcl.VclTimingPlan timing,
            VclWindowPlan.Window window,
            UUID binding,
            long revision,
            boolean retraction) {
        return WorkerSustainedFinalization.idleSustained(
                store, journal, timing, window, binding, revision, retraction);
    }

    record ReviewedInput(
            VclDocument document,
            SourceBindingSnapshots.Target target,
            SourceCaptureSnapshot start,
            AutomationGrantSnapshot grant,
            EvidencePolicySnapshot policy,
            String aggregate,
            String session,
            boolean zone,
            String absence,
            String sustained,
            String interval,
            boolean references) {
        RuleCompilationSnapshot snapshot(SourceCaptureSnapshot journal) {
            return new RuleCompilationSnapshot(
                    document,
                    null,
                    null,
                    grant,
                    policy,
                    List.of(target),
                    List.of(),
                    Map.of(target, journal));
        }
    }

    ReviewedInput reviewedInput(JdbcRuleDeployments.Deployment active) {
        if (active.start() == null) throw new VclException("START_CHECKPOINT_UNAVAILABLE", "");
        var bundle =
                bundles.find(active.account(), active.bundle())
                        .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest()))
            throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var parser = new VclParser();
        var compiled = bundle.json().path("compilation");
        var document = parser.parse(compiled.path("canonicalDocument").textValue());
        var inputs = deployments.readStartRecipe(active.account(), active.epoch()).inputs();
        if (!active.start().checkpointDigest().equals(inputs.checkpointDigest())
                || inputs.cuts().size() != 1
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
            if ("emit_alert".equals(node.path("method").textValue()))
                policy = uuid(node.at("/config/evidencePolicyId").textValue());
            if ("window_aggregate".equals(node.path("method").textValue()))
                aggregate = node.path("id").textValue();
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
            if (java.util.Set.of("window_session", "zone_session")
                    .contains(node.path("method").textValue())) {
                if (session != null) throw new VclException("SESSION_PROFILE_UNAVAILABLE", "");
                session = node.path("id").textValue();
                zone = "zone_session".equals(node.path("method").textValue());
            }
        }
        int profiles =
                (aggregate == null ? 0 : 1)
                        + (session == null ? 0 : 1)
                        + (sustained == null ? 0 : 1)
                        + (interval == null ? 0 : 1);
        if (policy == null
                || profiles != 1
                || interval == null && aggregate == null && !inputs.references().isEmpty()) {
            throw new VclException("WINDOW_GRAPH_UNSUPPORTED", "");
        }
        var start =
                captures.capture(
                        active.account(),
                        active.epoch(),
                        target.id(),
                        target.revision(),
                        inputs.cuts().get(target));
        UUID policyId = policy;
        var grant =
                new AutomationGrantSnapshot(
                        grants.read(active.account(), active.epoch()),
                        () -> grants.read(active.account(), active.epoch()));
        var evidence =
                new EvidencePolicySnapshot(
                        policies.read(active.account(), active.epoch(), policyId),
                        () -> policies.read(active.account(), active.epoch(), policyId));
        return new ReviewedInput(
                document,
                target,
                start,
                grant,
                evidence,
                aggregate,
                session,
                zone,
                absence,
                sustained,
                interval,
                !inputs.references().isEmpty());
    }

    WorkerCircleReads circles() {
        if (circles == null) throw new VclException("WORKER_SPATIAL_OWNER_UNAVAILABLE", "");
        return circles;
    }

    public DurableJournalWindow restoreWindow(
            JdbcRuleDeployments.Deployment candidate,
            KeyedStateStore store,
            KeyedStateStore.Key key,
            String pins,
            VclCompiler compiler) {
        if (candidate == null
                || store == null
                || key == null
                || pins == null
                || compiler == null
                || !candidate.account().equals(key.accountId())
                || !candidate.rule().equals(key.ruleId())
                || !candidate.epoch().equals(key.deploymentEpoch())) {
            throw new VclException("WORKER_TARGET_INVALID", "");
        }
        var active = current(candidate);
        var bundle =
                bundles.find(active.account(), active.bundle())
                        .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest()))
            throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var root = bundle.json();
        var compiled = root.path("compilation");
        var recipe = DurableJournalWindow.recipe(store.inspect(key, pins).state());
        VclDocument document = new VclParser().parse(recipe.document());
        if (document.kind() != VclDocument.Kind.RULE
                || !recipe.document().equals(compiled.path("canonicalDocument").textValue())
                || !active.rule().toString().equals(document.spec().path("ruleId").textValue())
                || !active.grant()
                        .toString()
                        .equals(document.spec().path("executionGrantId").textValue())
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
                    if (!recipe.binding()
                                    .toString()
                                    .equals(node.at("/config/bindingId").textValue())
                            || recipe.schemaRevision()
                                    != node.at("/config/schemaRevision").longValue()) {
                        throw new VclException("WINDOW_RECIPE_INVALID", "");
                    }
                }
                case "lookup_asof" -> throw new VclException("ASOF_SNAPSHOT_REQUIRED", "");
                case "emit_alert" -> {
                    var id = uuid(node.at("/config/evidencePolicyId").textValue());
                    if (policy != null && !policy.equals(id))
                        throw new VclException("WORKER_POLICY_NOT_PINNED", "");
                    policy = id;
                }
                default -> {}
            }
        }
        if (sources != 1 || policy == null) throw new VclException("WINDOW_RECIPE_INVALID", "");
        var grant =
                new AutomationGrantSnapshot(
                        grants.read(active.account(), active.epoch()),
                        () -> grants.read(active.account(), active.epoch()));
        UUID policyId = policy;
        var evidence =
                new EvidencePolicySnapshot(
                        policies.read(active.account(), active.epoch(), policyId),
                        () -> policies.read(active.account(), active.epoch(), policyId));
        var target = new SourceBindingSnapshots.Target(recipe.binding(), recipe.schemaRevision());
        var journal =
                captures.capture(
                        active.account(),
                        active.epoch(),
                        recipe.binding(),
                        recipe.schemaRevision(),
                        recipe.cut());
        var snapshot =
                new RuleCompilationSnapshot(
                        document,
                        null,
                        null,
                        grant,
                        evidence,
                        List.of(target),
                        List.of(),
                        Map.of(target, journal));
        var batch =
                snapshot.prepareJournalWindowBatch(
                        compiler,
                        recipe.nodeId(),
                        recipe.membershipBudget(),
                        active.start() != null);
        if (!(store instanceof JdbcKeyedStateStore jdbc)) {
            throw new VclException("WORKER_TRANSACTION_UNAVAILABLE", "");
        }
        var restored =
                batch.durable(
                        store,
                        active.epoch(),
                        recipe.subject(),
                        recipe.window(),
                        recipe.coverageProof(),
                        () -> current(active),
                        (targetKey, lease, transition, watermark) ->
                                jdbc.commitJournalWindow(
                                        targetKey,
                                        lease,
                                        transition,
                                        recipe.window().startInclusive(),
                                        active,
                                        watermark),
                        active.start() == null ? null : active.bundleDigest(),
                        () ->
                                captures.findCoverage(
                                        active.account(),
                                        active.epoch(),
                                        recipe.binding(),
                                        recipe.schemaRevision(),
                                        journal.inputGeneration(),
                                        recipe.window().startInclusive(),
                                        batch.closeDueAt(recipe.window()),
                                        VclObservationReadiness.checkpoint(
                                                journal.capturedHeadCheckpoint())));
        if (!key.equals(restored.key()) || !pins.equals(restored.pinsDigest()))
            throw new VclException("KEY_PINS_CHANGED", "");
        current(active);
        return restored;
    }

    JdbcRuleDeployments.Deployment current(JdbcRuleDeployments.Deployment expected) {
        var current =
                deployments
                        .find(expected.account(), expected.epoch())
                        .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!expected.equals(current) || !current.acceptsExistingWork()) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        return current;
    }

    static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        }
    }
}
