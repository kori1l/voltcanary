package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarDecimalPolicy;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclIntervalUnionPlan;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.analytics.vcl.VclWindowAggregateKernel;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.IntervalInput;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.Metadata;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.RetainedWindowInput;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.WindowPreview;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceCoverage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RuleJournalWindowBatch {

    private final RuleCompilationSnapshot snapshot;
    final VclWindowAggregateKernel.Plan plan;
    final long maximumMemberships;
    final SourceCaptureSnapshot journal;
    final VclTimingPlan timing;
    final String subjectField;
    final String valueField;
    final VclCompilation compilation;
    final VclCompilation.Node aggregateNode;
    final ScalarDecimalPolicy decimalPolicy;
    final IntervalInput input;
    final Map<String, java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>> filters;
    final RulePreparedLookups prepared;
    final Metadata owner;

    RuleJournalWindowBatch(
        RuleCompilationSnapshot snapshot,
        VclWindowAggregateKernel.Plan plan,
        long maximumMemberships,
        SourceCaptureSnapshot journal,
        VclTimingPlan timing,
        String subjectField,
        String valueField,
        VclCompilation compilation,
        VclCompilation.Node aggregateNode,
        ScalarDecimalPolicy decimalPolicy,
        IntervalInput input,
        RulePreparedLookups prepared,
        Metadata owner
    ) {
        this.snapshot = snapshot;
        this.plan = plan;
        this.maximumMemberships = maximumMemberships;
        this.journal = journal;
        this.timing = timing;
        this.subjectField = subjectField;
        this.valueField = valueField;
        this.compilation = compilation;
        this.aggregateNode = aggregateNode;
        this.decimalPolicy = decimalPolicy;
        this.input = input;
        this.prepared = prepared;
        this.owner = owner;
        this.filters = snapshot.intervalFilters(compilation, input, owner);
    }

    public VclWindowAggregateKernel.Plan plan() {
        return plan;
    }

    public long maximumMemberships() {
        return maximumMemberships;
    }

    record AggregateRoute(ScalarValue subject, List<VclWindowPlan.Window> windows) {}

    AggregateRoute route(JsonNode observation) {
        var eventTime = Instant.parse(observation.path("eventTime").textValue());
        var recordedAt = Instant.parse(observation.path("recordedAt").textValue());
        if (timing.classify(eventTime, recordedAt) == VclTimingPlan.Admission.QUARANTINED_FUTURE) {
            return new AggregateRoute(null, List.of());
        }
        ScalarValue subject;
        if (prepared != null) {
            UUID id = UUID.fromString(observation.path("inputEventId").textValue());
            var rows = RuleCompilationSnapshot.expandIntervalInput(
                prepared,
                input.steps(),
                RuleCompilationSnapshot.sustainedSample(observation, id, eventTime),
                1,
                new StringBuilder(),
                filters
            );
            if (rows.isEmpty()) return new AggregateRoute(null, List.of());
            subject = rows.getFirst().fields().get(UUID.fromString(subjectField));
        } else {
            var identity = RuleCompilationSnapshot.observationField(observation, subjectField);
            if (!filters.isEmpty() && filterWindowObservation(observation, eventTime).isEmpty()) {
                return new AggregateRoute(null, List.of());
            }
            subject = RuleCompilationSnapshot.observationValue(
                identity,
                UUID.fromString(observation.path("inputEventId").textValue())
            );
        }
        if (subject != null && subject.isFailure()) throw new VclException(subject.reason(), "");
        var windows = plan.windows().assign(eventTime).stream().distinct().toList();
        return new AggregateRoute(
            subject == null || subject.status() != ScalarValue.Status.OK || subject.isNull()
                ? null
                : subject,
            windows
        );
    }

    private List<VclIntervalUnionPlan.Row> filterWindowObservation(
        JsonNode observation,
        Instant eventTime
    ) {
        UUID id = UUID.fromString(observation.path("inputEventId").textValue());
        return RuleCompilationSnapshot.expandIntervalInput(
            null,
            input.steps(),
            RuleCompilationSnapshot.sustainedSample(observation, id, eventTime),
            1,
            new StringBuilder(),
            filters
        );
    }

    String evaluatorPlanDigest() {
        Instant end = Instant.EPOCH.plus(
            plan.windows().maximumSpanUs(),
            java.time.temporal.ChronoUnit.MICROS
        );
        if (
            timing
                .windowCloseDueAt(end)
                .isAfter(Instant.EPOCH.plus(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL))
        ) {
            throw new VclException("WINDOW_COVERAGE_PROFILE_UNAVAILABLE", "");
        }
        var decision = JournalWindowDecision.compileShape(
            compilation,
            aggregateNode,
            decimalPolicy,
            owner
        );
        var terminal = compilation
            .graph()
            .stream()
            .filter(node -> node.id().equals(decision.terminalId()))
            .findFirst()
            .orElseThrow();
        if (!"RULE_SUBJECT_OCCURRENCE".equals(terminal.config().path("dedup").textValue())) {
            throw new VclException("WINDOW_ALERT_DEDUP_UNSUPPORTED", "");
        }
        return VclCanonical.sha256(
            String.join(
                "\n",
                "DIRECT_JOURNAL_WINDOW_EVALUATOR/1",
                plan.digest(),
                timing.digest(),
                decision.digest()
            )
        );
    }

    public DurableJournalWindow durable(
        com.voltcanary.analytics.vcl.KeyedStateStore store,
        UUID executionEpoch,
        String subject,
        VclWindowPlan.Window window,
        UUID coverageProof
    ) {
        DurableJournalWindow.Commit commit =
            store instanceof JdbcKeyedStateStore jdbc
                ? (key, lease, transition, watermark) ->
                      jdbc.commitJournalWindow(
                          key,
                          lease,
                          transition,
                          window.startInclusive(),
                          null,
                          watermark
                      )
                : (key, lease, transition, watermark) -> store.commit(key, lease, transition);
        return durable(
            store,
            executionEpoch,
            subject,
            window,
            coverageProof,
            () -> {},
            commit,
            null
        );
    }

    DurableJournalWindow durable(
        com.voltcanary.analytics.vcl.KeyedStateStore store,
        UUID executionEpoch,
        String subject,
        VclWindowPlan.Window window,
        UUID coverageProof,
        Runnable deploymentCheck,
        DurableJournalWindow.Commit commit,
        String reviewedBundleDigest
    ) {
        return durable(
            store,
            executionEpoch,
            subject,
            window,
            coverageProof,
            deploymentCheck,
            commit,
            reviewedBundleDigest,
            () -> null
        );
    }

    Instant closeDueAt(VclWindowPlan.Window window) {
        return timing.windowCloseDueAt(window.endExclusive());
    }

    record CoveredAggregate(
        Instant through,
        SourceCaptureSnapshot.Cut cut,
        String inputDigest,
        VclSourceCoverage proof,
        ScalarValue value,
        JsonNode decision
    ) {}

    boolean usesCountPrefixes() {
        var config = aggregateNode.config();
        return (
            "COUNT".equals(config.path("reducer").textValue()) &&
            "LOWER_BOUND_PROVEN".equals(config.path("emit").textValue())
        );
    }

    void stageAggregateWindow(
        JdbcKeyedStateStore store,
        JdbcRuleDeployments.Deployment deployment,
        ScalarValue scalar,
        VclWindowPlan.Window window,
        long checkpoint,
        JsonNode event,
        UUID holder
    ) {
        var key = new KeyedStateStore.Key(
            deployment.account(),
            deployment.rule(),
            deployment.epoch(),
            aggregateSubjectDigest(scalar),
            IntervalWindowSchedule.occurrence(window)
        );
        var recipe = new IntervalWindowSchedule.Recipe(
            aggregateNode.id(),
            evaluatorPlanDigest(),
            journal.inputGeneration(),
            window,
            closeDueAt(window),
            VclCanonical.encode(scalar.literal()),
            usesCountPrefixes() &&
                java.time.temporal.ChronoUnit.MICROS.between(
                    window.startInclusive(),
                    window.endExclusive()
                ) >= 16
                ? 1
                : 0,
            null,
            0,
            null
        );
        owner.reauthorizeInputs();
        var route =
            prepared == null
                ? null
                : IntervalWindowSchedule.Route.from(
                      UUID.fromString(input.source().config().path("bindingId").textValue()),
                      event,
                      owner::reauthorizeInputs
                  );
        IntervalWindowSchedule.stage(store, deployment, key, recipe, checkpoint, holder, route);
    }

    String aggregateSubjectDigest(ScalarValue subject) {
        return usesCountPrefixes() || !"TEXT".equals(subject.literal().path("type").textValue())
            ? VclWindowSessionKernel.identity(subject)
            : VclCanonical.sha256(subject.literal().path("value").textValue());
    }

    JdbcJournalWatermarks.Candidate coveredFrontier(
        KeyedStateStore.Key key,
        VclWindowPlan.Window window,
        Instant through,
        VclSourceCoverage proof,
        String evidenceDigest
    ) {
        var dependency = timing
            .requiredInputs()
            .stream()
            .filter(dep -> dep.kind() == VclMetadata.Resource.BINDING)
            .findFirst()
            .orElseThrow(() -> new VclException("WINDOW_FRONTIER_PENDING", ""));
        var evidence = proof.evidence();
        var frontierAt = through;
        if (prepared == null) {
            var frontier = timing.watermark(
                List.of(
                    new VclTimingPlan.Progress(dependency, evidence.until(), proof.acceptedAt())
                ),
                timing.digest(),
                compilation.accessScopeDigest()
            );
            if (frontier.status() != ScalarValue.Status.OK) throw new VclException(
                "WINDOW_FRONTIER_PENDING",
                ""
            );
            frontierAt = Instant.parse(frontier.literal().path("value").textValue());
        }
        String scope = VclCanonical.sha256(
            String.join(
                "\n",
                "RULE_JOURNAL_WATERMARK/1",
                key.ruleId().toString(),
                progressScope(),
                dependency.id().toString(),
                Long.toString(dependency.revision()),
                proof.snapshotId().toString(),
                proof.inputGeneration().toString(),
                evidence.position().epoch().toString(),
                VclSourceCoverage.SOURCE_EVENT_TIME
            )
        );
        return new JdbcJournalWatermarks.Candidate(
            scope,
            evidence.from(),
            evidence.until(),
            frontierAt,
            evidenceDigest,
            new VclWindowPlan.Window(window.startInclusive(), through)
        );
    }

    private String progressScope() {
        return VclCanonical.sha256(
            String.join(
                "\n",
                VclTimingPlan.VERSION,
                compilation.documentDigest(),
                compilation.registryDigest(),
                compilation.unitRegistryDigest(),
                snapshot.ownerScope,
                snapshot.grantPin.snapshotDigest(),
                snapshot.policyPin.snapshotDigest()
            )
        );
    }

    Optional<CoveredAggregate> evaluateCovered(
        ScalarValue subject,
        VclWindowPlan.Window window,
        Instant through,
        long minimumCheckpoint,
        java.util.function.BiFunction<Instant, Instant, UUID> discoverProof
    ) {
        boolean text = "TEXT".equals(subject.literal().path("type").textValue());
        validateWindow(text ? subject.literal().path("value").textValue() : "", window);
        boolean lowerBound = through.isBefore(window.endExclusive());
        if (
            !through.isAfter(window.startInclusive()) ||
            through.isAfter(window.endExclusive()) ||
            (lowerBound && !usesCountPrefixes())
        ) {
            throw new VclException("WINDOW_LOWER_BOUND_UNAVAILABLE", "");
        }
        var prefix = new VclWindowPlan.Window(window.startInclusive(), through);
        var due = timing.windowCloseDueAt(through);
        UUID proofId = discoverProof.apply(prefix.startInclusive(), due);
        if (proofId == null) return Optional.empty();
        var base =
            minimumCheckpoint > VclObservationReadiness.checkpoint(journal.capturedHeadCheckpoint())
                ? journal.atCheckpoint(Long.toString(minimumCheckpoint))
                : journal;
        var proof = base.coverage(proofId, prefix.startInclusive(), due, true);
        if (
            !proof.qualifiesEventTime() ||
            timing.classify(proof.evidence().until(), proof.acceptedAt()) ==
                VclTimingPlan.Admission.QUARANTINED_FUTURE
        ) return Optional.empty();
        var covered = base.forFinalization(proof);
        var retained = readRetainedInput(
            covered,
            RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES,
            prefix
        );
        // The reducer still checks the assigned calendar or rolling window. Only the supplied input
        // is clipped.
        var evaluated = reduceWindow(retained, subject, window, lowerBound);
        var predicate = JournalWindowDecision.compileTyped(
            compilation,
            aggregateNode,
            decimalPolicy,
            owner,
            subject
        );
        var decision = lowerBound
            ? predicate.evaluateLowerBound(evaluated.localValue())
            : predicate.evaluate(evaluated.localValue());
        if (!proof.equals(covered.coverage(proofId, prefix.startInclusive(), due))) {
            throw new VclException("WINDOW_COVERAGE_CHANGED", "");
        }
        owner.reauthorizeInputs();
        return Optional.of(
            new CoveredAggregate(
                through,
                covered.cut(),
                evaluated.inputDigest(),
                proof,
                evaluated.localValue(),
                decision
            )
        );
    }

    DurableJournalWindow durable(
        com.voltcanary.analytics.vcl.KeyedStateStore store,
        UUID executionEpoch,
        String subject,
        VclWindowPlan.Window window,
        UUID coverageProof,
        Runnable deploymentCheck,
        DurableJournalWindow.Commit commit,
        String reviewedBundleDigest,
        java.util.function.Supplier<UUID> discoverProof
    ) {
        boolean requireFinalization = reviewedBundleDigest != null;
        validateWindow(subject, window);
        UUID rule = UUID.fromString(snapshot.document.spec().path("ruleId").textValue());
        var decision = JournalWindowDecision.compile(
            compilation,
            aggregateNode,
            decimalPolicy,
            snapshot.new Metadata(),
            subject
        );
        DurableJournalWindow.AlertIntent alertIntent = null;
        if (requireFinalization) {
            var terminal = compilation
                .graph()
                .stream()
                .filter(node -> node.id().equals(decision.terminalId()))
                .findFirst()
                .orElseThrow();
            if (!"RULE_SUBJECT_OCCURRENCE".equals(terminal.config().path("dedup").textValue())) {
                throw new VclException("WINDOW_ALERT_DEDUP_UNSUPPORTED", "");
            }
            alertIntent = new DurableJournalWindow.AlertIntent(
                reviewedBundleDigest,
                snapshot.grantPin.id(),
                snapshot.grantPin.revision(),
                snapshot.grantPin.snapshotDigest(),
                snapshot.policyPin.id(),
                snapshot.policyPin.snapshotDigest(),
                terminal.config().path("severity").textValue()
            );
        }
        var source = input.source().config();
        var recipe = new DurableJournalWindow.Recipe(
            compilation.canonicalDocument(),
            aggregateNode.id(),
            UUID.fromString(source.path("bindingId").textValue()),
            source.path("schemaRevision").longValue(),
            journal.cut(),
            maximumMemberships,
            subject,
            window,
            coverageProof
        );
        String progressScope = progressScope();
        return new DurableJournalWindow(
            store,
            journal.accountId(),
            rule,
            executionEpoch,
            subject,
            window,
            coverageProof,
            journal.cutDigest(),
            plan.digest(),
            timing,
            compilation.accessScopeDigest(),
            progressScope,
            decision.digest(),
            closeDueAt(window),
            byteBudget -> preview(subject, window, byteBudget, requireFinalization),
            decision,
            discoverProof,
            proof ->
                journal.coverage(
                    proof,
                    window.startInclusive(),
                    window.endExclusive(),
                    requireFinalization
                ),
            proof -> {
                var finalCut = journal.forFinalization(proof);
                var retained = readRetainedInput(
                    finalCut,
                    RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES,
                    window
                );
                return new DurableJournalWindow.FinalInput(
                    finalCut.cut(),
                    retained.inputDigest(),
                    reduceWindow(retained, textSubject(subject), window, false).localValue()
                );
            },
            () -> {
                snapshot.reauthorize();
                deploymentCheck.run();
            },
            recipe,
            commit,
            alertIntent
        );
    }

    /**
     * Read the plan's original source cut under fresh ownership and the original field
     * snapshot.grant.
     */
    public VclObservationPage readPage(String afterCheckpoint, int limit) {
        snapshot.reauthorize();
        var page = journal.readPage(afterCheckpoint, limit);
        snapshot.reauthorize();
        return page;
    }

    /** Materialize the complete retained generation prefix within row and encoded-byte bounds. */
    public RetainedWindowInput readRetainedInput(long encodedByteBudget) {
        return readRetainedInput(journal, encodedByteBudget, null);
    }

    private RetainedWindowInput readRetainedInput(
        SourceCaptureSnapshot input,
        long encodedByteBudget,
        VclWindowPlan.Window range
    ) {
        if (
            encodedByteBudget <= 0 ||
            encodedByteBudget > RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES
        ) {
            throw new VclException("WINDOW_INPUT_BYTE_BUDGET_INVALID", "");
        }
        var pages = new ArrayList<VclObservationPage>();
        var eventIds = new HashSet<UUID>();
        String cutDigest = input.cutDigest();
        var fingerprints = new StringBuilder("RULE_JOURNAL_INPUT/1\n").append(cutDigest);
        if (range != null) fingerprints
            .append('\n')
            .append(range.startInclusive())
            .append('\n')
            .append(range.endExclusive());
        String after = input.generationStartCheckpoint();
        long remainingBytes = encodedByteBudget;
        VclObservationPage page;
        do {
            owner.reauthorizeInputs();
            page =
                range == null
                    ? input.readPage(after, 128)
                    : input.readPage(after, 128, range.startInclusive(), range.endExclusive());
            int bytes = page.bytes().length;
            if (bytes > remainingBytes) throw new VclException(
                "WINDOW_INPUT_BYTE_BUDGET_EXCEEDED",
                ""
            );
            remainingBytes -= bytes;
            for (var observation : page.observations()) {
                if (!eventIds.add(UUID.fromString(observation.path("inputEventId").textValue()))) {
                    throw new VclException("WINDOW_INPUT_EVENT_DUPLICATE", "");
                }
                if (range != null && eventIds.size() > VclWindowAggregateKernel.MAX_INPUTS) {
                    throw new VclException("WINDOW_BATCH_INPUT_LIMIT", "");
                }
                fingerprints
                    .append('\n')
                    .append(VclCanonical.sha256(VclCanonical.encode(observation)));
            }
            pages.add(page);
            after = page.nextCheckpoint();
        } while (page.hasMore());
        if (range == null && eventIds.size() != input.retainedObservationCount()) {
            throw new VclException("WINDOW_INPUT_CHECKPOINT_MISMATCH", "");
        }
        owner.reauthorizeInputs();
        return new RetainedWindowInput(
            cutDigest,
            VclCanonical.sha256(fingerprints.toString()),
            pages,
            eventIds.size(),
            encodedByteBudget - remainingBytes
        );
    }

    /** Evaluate one exact subject/window over the retained prefix for diagnostics. */
    public WindowPreview preview(
        String subject,
        VclWindowPlan.Window window,
        long encodedByteBudget
    ) {
        return preview(subject, window, encodedByteBudget, false);
    }

    WindowPreview preview(ScalarValue subject, VclWindowPlan.Window window, long byteBudget) {
        validateWindow(
            "TEXT".equals(subject.literal().path("type").textValue())
                ? subject.literal().path("value").textValue()
                : "",
            window
        );
        var evaluation = reduceWindow(
            readRetainedInput(journal, byteBudget, window),
            subject,
            window,
            false
        );
        owner.reauthorizeInputs();
        return evaluation;
    }

    WindowPreview preview(
        String subject,
        VclWindowPlan.Window window,
        long encodedByteBudget,
        boolean ranged
    ) {
        validateWindow(subject, window);
        var retained = readRetainedInput(journal, encodedByteBudget, ranged ? window : null);
        var evaluated = reduceWindow(retained, textSubject(subject), window, false);
        snapshot.reauthorize();
        return prepared == null
            ? new WindowPreview(retained.inputDigest(), evaluated.localValue())
            : evaluated;
    }

    private static ScalarValue textSubject(String subject) {
        return ScalarValue.known(
            RuleCompilationSnapshot.PARSER,
            JsonNodeFactory.instance
                .objectNode()
                .put("type", "TEXT")
                .put("value", subject)
                .toString()
        );
    }

    private WindowPreview reduceWindow(
        RetainedWindowInput retained,
        ScalarValue subject,
        VclWindowPlan.Window window,
        boolean lowerBound
    ) {
        var fingerprints = new StringBuilder("RULE_TYPED_AGGREGATE/1\n")
            .append(retained.inputDigest())
            .append('\n')
            .append(compilation.accessScopeDigest())
            .append('\n')
            .append(VclCanonical.encode(subject.literal()));
        var rows = new ArrayList<VclWindowAggregateKernel.Input>();
        UUID subjectId = UUID.fromString(subjectField);
        UUID valueId = valueField == null ? null : UUID.fromString(valueField);
        ScalarValue unresolved = null;
        for (var page : retained.pages()) {
            for (var event : page.observations()) {
                var at = Instant.parse(event.path("eventTime").textValue());
                if (!window.contains(at)) continue;
                UUID id = UUID.fromString(event.path("inputEventId").textValue());
                var sample = RuleCompilationSnapshot.sustainedSample(event, id, at);
                if (
                    prepared == null && otherSubject(sample.fields().get(subjectId), subject)
                ) continue;
                for (var row : RuleCompilationSnapshot.expandIntervalInput(
                    prepared,
                    input.steps(),
                    sample,
                    1,
                    fingerprints,
                    filters
                )) {
                    var identity = row.fields().get(subjectId);
                    boolean knownSubject =
                        identity != null &&
                        identity.status() == ScalarValue.Status.OK &&
                        !identity.isNull();
                    if (knownSubject && !subject.literal().equals(identity.literal())) continue;
                    String reason = null;
                    if (row.eligibility() != null) {
                        reason = "WINDOW_FILTER_UNKNOWN";
                    } else if (!knownSubject) {
                        reason = "WINDOW_SUBJECT_UNKNOWN";
                    } else if (
                        timing.classify(at, sample.recordedAt()) ==
                        VclTimingPlan.Admission.QUARANTINED_FUTURE
                    ) {
                        reason = "WINDOW_FUTURE_OBSERVATION";
                    }
                    if (reason != null) {
                        if (unresolved == null) unresolved = ScalarValue.unknown(
                            reason,
                            List.of(id)
                        );
                        continue;
                    }
                    rows.add(
                        new VclWindowAggregateKernel.Input(
                            id,
                            at,
                            valueId == null ? null : row.fields().get(valueId)
                        )
                    );
                }
            }
        }
        var context = new VclWindowAggregateKernel.Context(
            VclWindowAggregateKernel.VERSION,
            plan.digest(),
            compilation.accessScopeDigest(),
            window
        );
        var result = new VclWindowAggregateKernel().evaluate(
            plan,
            context,
            rows,
            compilation.accessScopeDigest()
        );
        if (!lowerBound && !result.isFailure() && unresolved != null) result = unresolved;
        if (prepared != null) prepared.verifyManifests();
        return new WindowPreview(VclCanonical.sha256(fingerprints.toString()), result);
    }

    private static boolean otherSubject(ScalarValue identity, ScalarValue subject) {
        return (
            identity != null &&
            identity.status() == ScalarValue.Status.OK &&
            !identity.isNull() &&
            !subject.literal().equals(identity.literal())
        );
    }

    private void validateWindow(String subject, VclWindowPlan.Window window) {
        if (
            subject == null ||
            subject.codePointCount(0, subject.length()) > 512 ||
            !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(subject)
        ) {
            throw new VclException("WINDOW_SUBJECT_INVALID", "");
        }
        if (window == null || !plan.windows().assign(window.startInclusive()).contains(window)) {
            throw new VclException("AGGREGATE_WINDOW_CHANGED", "");
        }
    }
}
