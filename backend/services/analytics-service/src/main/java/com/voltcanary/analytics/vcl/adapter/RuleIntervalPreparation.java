package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarDecimalPolicy;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclIntervalUnionKernel;
import com.voltcanary.analytics.vcl.VclIntervalUnionPlan;
import com.voltcanary.analytics.vcl.VclSustainedPlan;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.IntervalFinalization;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.IntervalInput;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.Metadata;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.WindowPreview;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationReadiness;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class RuleIntervalPreparation {

    private final RuleCompilationSnapshot snapshot;

    RuleIntervalPreparation(RuleCompilationSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    VclIntervalUnionPlan prepareIntervalUnion(VclCompiler compiler, String nodeId) {
        return VclIntervalUnionPlan.compile(
            snapshot.compile(compiler),
            nodeId,
            snapshot.new Metadata()
        );
    }

    WindowPreview previewDirectIntervalUnion(
        VclCompiler compiler,
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        long byteBudget
    ) {
        return previewJournalIntervalUnion(
            snapshot.compile(compiler),
            snapshot.new Metadata(),
            null,
            nodeId,
            subjectDigest,
            window,
            byteBudget,
            null,
            null,
            window.endExclusive()
        );
    }

    String sourceCutDigest() {
        if (snapshot.journals.size() != 1) throw new VclException(
            "WINDOW_DIRECT_JOURNAL_REQUIRED",
            ""
        );
        return snapshot.journals.values().iterator().next().cutDigest();
    }

    void stageIntervalWindows(
        VclCompiler compiler,
        String nodeId,
        SourceBindingSnapshots.Target target,
        JdbcRuleDeployments.Deployment deployment,
        JdbcKeyedStateStore store,
        JsonNode event,
        UUID holder
    ) {
        stageIntervalWindows(
            snapshot.compile(compiler),
            snapshot.new Metadata(),
            null,
            nodeId,
            target,
            deployment,
            store,
            event,
            holder
        );
    }

    void stageIntervalWindows(
        VclCompilation compilation,
        Metadata owner,
        RulePreparedLookups prepared,
        String nodeId,
        SourceBindingSnapshots.Target target,
        JdbcRuleDeployments.Deployment deployment,
        JdbcKeyedStateStore store,
        JsonNode event,
        UUID holder
    ) {
        var plan = VclIntervalUnionPlan.compile(compilation, nodeId, owner);
        var timing = VclTimingPlan.compile(compilation);
        var journal = snapshot.journals.get(target);
        if (journal == null || !deployment.account().equals(journal.accountId())) {
            throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        }
        var definition = journal.binding(target.id(), target.revision()).orElseThrow().definition();
        var contract = com.voltcanary.vcl.VclSourceBindingProfile.intervalFields(
            definition
        ).orElseThrow(() -> new VclException("INTERVAL_REPORT_CONTRACT_REQUIRED", ""));
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var eventTime = Instant.parse(event.path("eventTime").textValue());
        if (
            timing.classify(eventTime, Instant.parse(event.path("recordedAt").textValue())) ==
            VclTimingPlan.Admission.QUARANTINED_FUTURE
        ) return;
        var operator = intervalOperator(compilation, nodeId);
        var input = intervalInput(compilation, operator, prepared);
        int firstPrefix = "LOWER_BOUND_PROVEN".equals(operator.config().path("emit").textValue())
            ? 1
            : 0;
        if (
            !target.id().toString().equals(input.source().config().path("bindingId").textValue()) ||
            target.revision() != input.source().config().path("schemaRevision").longValue()
        ) {
            throw new VclException("INTERVAL_SOURCE_COVERAGE_UNAVAILABLE", "");
        }
        var sample = RuleCompilationSnapshot.sustainedSample(event, id, eventTime);
        requireIntervalReport(
            new VclIntervalUnionPlan.Row(id, sample.fields()),
            contract,
            eventTime
        );
        var rows = expandIntervalInput(
            prepared,
            input.steps(),
            sample,
            VclIntervalUnionKernel.MAX_INPUTS,
            new StringBuilder(),
            intervalFilters(compilation, input, owner)
        );
        var generation = journal.inputGeneration();
        var route =
            prepared == null
                ? null
                : IntervalWindowSchedule.Route.from(target.id(), event, owner::reauthorizeInputs);
        var assignments = new LinkedHashMap<KeyedStateStore.Key, IntervalWindowSchedule.Recipe>();
        var unresolvedWindows = new java.util.LinkedHashSet<String>();
        int memberships = 0;
        for (var row : rows) {
            var subjectValue = row.fields().get(plan.subjectField());
            var start = intervalBound(row, contract.start());
            var end = intervalBound(row, contract.end());
            if (subjectValue != null && subjectValue.isFailure()) throw new VclException(
                subjectValue.reason(),
                ""
            );
            boolean unresolved =
                subjectValue == null ||
                subjectValue.status() != ScalarValue.Status.OK ||
                subjectValue.isNull();
            if (start == null || end == null) throw new VclException(
                "INTERVAL_REPORT_BOUNDS_CHANGED",
                ""
            );
            for (var window : plan.overlappingWindows(start, end)) {
                if (!deployment.includesWindow(window.startInclusive())) continue;
                if (++memberships > VclIntervalUnionKernel.MAX_INPUTS) throw new VclException(
                    "WINDOW_FANOUT_LIMIT",
                    ""
                );
                if (unresolved) {
                    unresolvedWindows.add(IntervalWindowSchedule.occurrence(window));
                    continue;
                }
                String subject = VclWindowSessionKernel.identity(subjectValue);
                var dueAt = timing.windowCloseDueAt(plan.reportingHorizon(window, definition));
                var key = new KeyedStateStore.Key(
                    deployment.account(),
                    deployment.rule(),
                    deployment.epoch(),
                    subject,
                    IntervalWindowSchedule.occurrence(window)
                );
                assignments.putIfAbsent(
                    key,
                    new IntervalWindowSchedule.Recipe(
                        nodeId,
                        plan.digest(),
                        generation,
                        window,
                        dueAt,
                        VclCanonical.encode(subjectValue.literal()),
                        firstPrefix,
                        null,
                        0,
                        null
                    )
                );
            }
        }
        long checkpoint = VclObservationReadiness.checkpoint(event.path("checkpoint").textValue());
        for (var assignment : assignments.entrySet()) {
            owner.reauthorizeInputs();
            IntervalWindowSchedule.stage(
                store,
                deployment,
                assignment.getKey(),
                assignment.getValue(),
                checkpoint,
                holder,
                route
            );
        }
        for (var candidate : store.registeredWindows(deployment, List.copyOf(unresolvedWindows))) {
            owner.reauthorizeInputs();
            IntervalWindowSchedule.stageCorrected(store, deployment, candidate, checkpoint, holder);
        }
    }

    static Instant intervalBound(VclIntervalUnionPlan.Row row, UUID field) {
        var value = row.fields().get(field);
        return value == null || value.status() != ScalarValue.Status.OK || value.isNull()
            ? null
            : Instant.parse(value.literal().path("value").textValue());
    }

    static VclWindowPlan.Window correctionRange(
        JsonNode event,
        SourceCaptureSnapshot journal,
        SourceBindingSnapshots.Target target,
        boolean interval
    ) {
        var at = Instant.parse(event.path("eventTime").textValue());
        if (!interval) return new VclWindowPlan.Window(
            at,
            at.plus(1, java.time.temporal.ChronoUnit.MICROS)
        );
        var definition = journal
            .binding(target.id(), target.revision())
            .orElseThrow(() -> new VclException("SOURCE_CAPTURE_UNAVAILABLE", ""))
            .definition();
        var contract = com.voltcanary.vcl.VclSourceBindingProfile.intervalFields(
            definition
        ).orElseThrow(() -> new VclException("INTERVAL_REPORT_CONTRACT_REQUIRED", ""));
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var row = new VclIntervalUnionPlan.Row(
            id,
            RuleCompilationSnapshot.observationFields(event, id)
        );
        return requireIntervalReport(row, contract, at);
    }

    static VclWindowPlan.Window requireIntervalReport(
        VclIntervalUnionPlan.Row row,
        com.voltcanary.vcl.VclSourceBindingProfile.IntervalFields contract,
        Instant eventTime
    ) {
        var start = intervalBound(row, contract.start());
        var end = intervalBound(row, contract.end());
        if (
            start == null ||
            end == null ||
            !start.isBefore(end) ||
            !end.equals(eventTime) ||
            java.time.Duration.between(start, end).compareTo(
                java.time.Duration.of(contract.maxSpanUs(), java.time.temporal.ChronoUnit.MICROS)
            ) > 0
        ) {
            throw new VclException("INTERVAL_REPORT_BOUNDS_CHANGED", "");
        }
        return new VclWindowPlan.Window(start, end);
    }

    JournalWindowDecision intervalDecision(
        VclCompiler compiler,
        String nodeId,
        ScalarValue subject
    ) {
        var compilation = snapshot.compile(compiler);
        return JournalWindowDecision.compileTyped(
            compilation,
            intervalOperator(compilation, nodeId),
            intervalDecimalPolicy(),
            snapshot.new Metadata(),
            subject
        );
    }

    String intervalEvaluatorPlanDigest(VclCompiler compiler, String nodeId, String bundleDigest) {
        return intervalEvaluatorPlanDigest(
            snapshot.compile(compiler),
            snapshot.new Metadata(),
            null,
            nodeId,
            bundleDigest
        );
    }

    String intervalEvaluatorPlanDigest(
        VclCompilation compilation,
        Metadata owner,
        RulePreparedLookups prepared,
        String nodeId,
        String bundleDigest
    ) {
        var operator = intervalOperator(compilation, nodeId);
        var input = intervalInput(compilation, operator, prepared);
        var methods = compilation
            .graph()
            .stream()
            .map(VclCompilation.Node::method)
            .filter(method -> !java.util.Set.of("lookup_asof", "filter").contains(method))
            .toList();
        if (
            methods.size() != 4 ||
            !java.util.Set.copyOf(methods).equals(
                java.util.Set.of("read_stream", "interval_union_window", "compare", "emit_alert")
            ) ||
            compilation.graph().size() != methods.size() + input.steps().size() ||
            !java.util.Set.of("ON_CLOSE", "LOWER_BOUND_PROVEN").contains(
                operator.config().path("emit").asText()
            )
        ) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        var decision = JournalWindowDecision.compileShape(
            compilation,
            operator,
            intervalDecimalPolicy(),
            owner
        );
        intervalFilters(compilation, input, owner);
        snapshot
            .sessionAlertIntent(decision.nodeId(), bundleDigest)
            .orElseThrow(() -> new VclException("EVALUATOR_PROFILE_UNAVAILABLE", ""));
        var plan = VclIntervalUnionPlan.compile(compilation, nodeId, owner);
        var timing = VclTimingPlan.compile(compilation);
        var sources = timing
            .requiredInputs()
            .stream()
            .filter(dependency -> dependency.kind() == VclMetadata.Resource.BINDING)
            .toList();
        if (sources.size() != 1) throw new VclException("INTERVAL_SOURCE_COVERAGE_UNAVAILABLE", "");
        var source = sources.getFirst();
        var journal = snapshot.journals.get(
            new SourceBindingSnapshots.Target(source.id(), source.revision())
        );
        if (source.kind() != VclMetadata.Resource.BINDING || journal == null) {
            throw new VclException("INTERVAL_SOURCE_COVERAGE_UNAVAILABLE", "");
        }
        var schema = journal.binding(source.id(), source.revision()).orElseThrow().definition();
        var latest = timing.windowCloseDueAt(
            Instant.EPOCH.plus(
                plan.maximumReportingRangeUs(schema),
                java.time.temporal.ChronoUnit.MICROS
            )
        );
        if (latest.isAfter(Instant.EPOCH.plus(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL))) {
            throw new VclException("INTERVAL_COVERAGE_RANGE_LIMIT", "");
        }
        owner.reauthorizeInputs();
        return VclCanonical.sha256(
            String.join(
                "\n",
                "DIRECT_JOURNAL_INTERVAL_EVALUATOR/1",
                plan.digest(),
                timing.digest(),
                decision.digest(),
                journal.cutDigest()
            )
        );
    }

    static VclCompilation.Node intervalOperator(VclCompilation compilation, String nodeId) {
        return compilation
            .graph()
            .stream()
            .filter(
                node -> node.id().equals(nodeId) && "interval_union_window".equals(node.method())
            )
            .findFirst()
            .orElseThrow(() -> new VclException("INTERVAL_OPERATOR_REQUIRED", ""));
    }

    ScalarDecimalPolicy intervalDecimalPolicy() {
        var decimal = snapshot.document.spec().path("decimalPolicy");
        return new ScalarDecimalPolicy(
            decimal.path("precision").intValue(),
            decimal.path("scale").intValue(),
            ScalarDecimalPolicy.Rounding.valueOf(decimal.path("rounding").textValue())
        );
    }

    Optional<IntervalFinalization> finalizeDirectIntervalUnion(
        VclCompiler compiler,
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        long minimumCheckpoint,
        java.util.function.BiFunction<Instant, Instant, UUID> discoverProof
    ) {
        return finalizeJournalIntervalUnion(
            snapshot.compile(compiler),
            snapshot.new Metadata(),
            null,
            nodeId,
            subjectDigest,
            window,
            window.endExclusive(),
            minimumCheckpoint,
            discoverProof
        );
    }

    Optional<IntervalFinalization> proveDirectIntervalPrefix(
        VclCompiler compiler,
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        Instant through,
        long minimumCheckpoint,
        java.util.function.BiFunction<Instant, Instant, UUID> discoverProof
    ) {
        var compilation = snapshot.compile(compiler);
        requireIntervalLowerBound(compilation, nodeId, window, through);
        return finalizeJournalIntervalUnion(
            compilation,
            snapshot.new Metadata(),
            null,
            nodeId,
            subjectDigest,
            window,
            through,
            minimumCheckpoint,
            discoverProof
        );
    }

    static void requireIntervalLowerBound(
        VclCompilation compilation,
        String nodeId,
        VclWindowPlan.Window window,
        Instant through
    ) {
        if (
            !"LOWER_BOUND_PROVEN".equals(
                intervalOperator(compilation, nodeId).config().path("emit").textValue()
            ) ||
            !through.isAfter(window.startInclusive()) ||
            !through.isBefore(window.endExclusive())
        ) {
            throw new VclException("INTERVAL_LOWER_BOUND_UNAVAILABLE", "");
        }
    }

    Optional<IntervalFinalization> finalizeJournalIntervalUnion(
        VclCompilation compilation,
        Metadata owner,
        RulePreparedLookups prepared,
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        Instant measuredThrough,
        long minimumCheckpoint,
        java.util.function.BiFunction<Instant, Instant, UUID> discoverProof
    ) {
        var plan = VclIntervalUnionPlan.compile(compilation, nodeId, owner);
        var timing = VclTimingPlan.compile(compilation);
        var sources = timing
            .requiredInputs()
            .stream()
            .filter(input -> input.kind() == VclMetadata.Resource.BINDING)
            .toList();
        if (sources.size() != 1 || (prepared == null && timing.requiredInputs().size() != 1)) {
            throw new VclException("INTERVAL_SOURCE_COVERAGE_UNAVAILABLE", "");
        }
        var source = sources.getFirst();
        var journal = snapshot.journals.get(
            new SourceBindingSnapshots.Target(source.id(), source.revision())
        );
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        if (
            minimumCheckpoint > VclObservationReadiness.checkpoint(journal.capturedHeadCheckpoint())
        ) {
            journal = journal.atCheckpoint(Long.toString(minimumCheckpoint));
        }
        var definition = journal.binding(source.id(), source.revision()).orElseThrow().definition();
        var horizon = plan.reportingHorizon(window, measuredThrough, definition);
        var from = window.startInclusive();
        var due = timing.windowCloseDueAt(horizon);
        if (
            java.time.Duration.between(from, due).compareTo(
                com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL
            ) > 0
        ) {
            throw new VclException("INTERVAL_COVERAGE_RANGE_LIMIT", "");
        }
        UUID proofId = discoverProof.apply(from, due);
        if (proofId == null) return Optional.empty();
        var proof = journal.coverage(proofId, from, due, true);
        if (!proof.qualifiesEventTime()) return Optional.empty();
        // The sealed reference manifests are a separate gate, not source event-time progress.
        if (
            proof.evidence().until().isBefore(due) ||
            timing.classify(proof.evidence().until(), proof.acceptedAt()) ==
                VclTimingPlan.Admission.QUARANTINED_FUTURE
        ) return Optional.empty();
        var covered = journal.forFinalization(proof);
        var result = previewJournalIntervalUnion(
            compilation,
            owner,
            prepared,
            nodeId,
            subjectDigest,
            window,
            RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES,
            covered,
            horizon,
            measuredThrough
        );
        if (!proof.equals(covered.coverage(proofId, from, due))) throw new VclException(
            "INTERVAL_COVERAGE_CHANGED",
            ""
        );
        if (prepared != null) prepared.verifyManifests();
        owner.reauthorizeInputs();
        return Optional.of(
            new IntervalFinalization(
                result,
                proof,
                covered.cutDigest(),
                covered.capturedHeadCheckpoint(),
                measuredThrough
            )
        );
    }

    WindowPreview previewJournalIntervalUnion(
        VclCompilation compilation,
        Metadata owner,
        RulePreparedLookups prepared,
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        long byteBudget,
        SourceCaptureSnapshot covered,
        Instant reportingHorizon,
        Instant measuredThrough
    ) {
        if (byteBudget <= 0 || byteBudget > RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES) {
            throw new VclException("WINDOW_INPUT_BYTE_BUDGET_INVALID", "");
        }
        var input = intervalInput(compilation, intervalOperator(compilation, nodeId), prepared);
        var filters = intervalFilters(compilation, input, owner);
        var source = input.source();
        var journal = snapshot.journals.get(
            new SourceBindingSnapshots.Target(
                UUID.fromString(source.config().path("bindingId").textValue()),
                source.config().path("schemaRevision").longValue()
            )
        );
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        if (covered != null) journal = covered;
        var plan = VclIntervalUnionPlan.compile(compilation, nodeId, owner);
        var horizon = reportingHorizon;
        if (horizon == null) {
            var definition = journal
                .binding(
                    UUID.fromString(source.config().path("bindingId").textValue()),
                    source.config().path("schemaRevision").longValue()
                )
                .orElseThrow()
                .definition();
            horizon = plan.reportingHorizon(window, measuredThrough, definition);
        }
        var timing = VclTimingPlan.compile(compilation);
        var fingerprints = new StringBuilder(
            String.join(
                "\n",
                "RULE_INTERVAL_UNION_JOURNAL/1",
                journal.cutDigest(),
                plan.digest(),
                subjectDigest,
                window.startInclusive().toString(),
                window.endExclusive().toString()
            )
        );
        if (!measuredThrough.equals(window.endExclusive())) fingerprints
            .append("\nprefix\n")
            .append(measuredThrough);
        var rows = new ArrayList<VclIntervalUnionPlan.Row>();
        UUID future = null;
        // A report can arrive after the measured window and still overlap it.
        String after = journal.generationStartCheckpoint();
        VclObservationPage page;
        do {
            page = journal.readPage(after, 128, window.startInclusive(), horizon);
            byteBudget -= page.bytes().length;
            if (byteBudget < 0) throw new VclException("WINDOW_INPUT_BYTE_BUDGET_EXCEEDED", "");
            for (var event : page.observations()) {
                var id = UUID.fromString(event.path("inputEventId").textValue());
                if (rows.size() >= VclIntervalUnionKernel.MAX_INPUTS) throw new VclException(
                    "INTERVAL_INPUT_LIMIT",
                    ""
                );
                var sample = RuleCompilationSnapshot.sustainedSample(
                    event,
                    id,
                    Instant.parse(event.path("eventTime").textValue())
                );
                if (
                    future == null &&
                    timing.classify(sample.at(), sample.recordedAt()) ==
                        VclTimingPlan.Admission.QUARANTINED_FUTURE
                ) {
                    future = id;
                }
                fingerprints.append('\n').append(VclCanonical.sha256(VclCanonical.encode(event)));
                rows.addAll(
                    expandIntervalInput(
                        prepared,
                        input.steps(),
                        sample,
                        VclIntervalUnionKernel.MAX_INPUTS - rows.size(),
                        fingerprints,
                        filters
                    )
                );
            }
            after = page.nextCheckpoint();
        } while (page.hasMore());
        var result = plan.evaluatePrefix(
            subjectDigest,
            window,
            measuredThrough,
            rows,
            owner.accessScopeDigest()
        );
        if (future != null && !result.isFailure()) result = ScalarValue.unknown(
            "INTERVAL_FUTURE_OBSERVATION",
            List.of(future)
        );
        owner.reauthorizeInputs();
        return new WindowPreview(VclCanonical.sha256(fingerprints.toString()), result);
    }

    static IntervalInput intervalInput(
        VclCompilation compilation,
        VclCompilation.Node operator,
        RulePreparedLookups prepared
    ) {
        var nodes = new java.util.HashMap<String, VclCompilation.Node>();
        compilation.graph().forEach(node -> nodes.put(node.id(), node));
        var lookups = new ArrayList<VclCompilation.Node>();
        var source = nodes.get(operator.inputs().get("events"));
        while (
            source != null &&
            ("filter".equals(source.method()) ||
                (prepared != null && "lookup_asof".equals(source.method())))
        ) {
            var temporal = source.config().path("temporal");
            if (
                "INTERVAL_SPLIT".equals(temporal.path("mode").textValue()) &&
                (!temporal.path("startFieldId").equals(operator.config().path("startFieldId")) ||
                    !temporal.path("endFieldId").equals(operator.config().path("endFieldId")))
            ) {
                throw new VclException("INTERVAL_LOOKUP_BOUNDS_CHANGED", "");
            }
            lookups.addFirst(source);
            source = nodes.get(source.inputs().get("events"));
        }
        if (source == null || !"read_stream".equals(source.method())) {
            throw new VclException(
                "window_aggregate".equals(operator.method())
                    ? "WINDOW_DIRECT_JOURNAL_REQUIRED"
                    : "INTERVAL_DIRECT_JOURNAL_REQUIRED",
                ""
            );
        }
        return new IntervalInput(source, List.copyOf(lookups));
    }

    Map<String, java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>> intervalFilters(
        VclCompilation compilation,
        IntervalInput input,
        Metadata owner
    ) {
        var filters = new java.util.HashMap<
            String,
            java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>
        >();
        var units = com.voltcanary.vcl.VclUnits.standard();
        var registry = new com.voltcanary.vcl.VclRegistry(RuleCompilationSnapshot.PARSER);
        var compiler = new com.voltcanary.vcl.VclScalarCompiler(
            RuleCompilationSnapshot.PARSER,
            registry,
            units
        );
        var evaluator = new com.voltcanary.analytics.vcl.VclScalarEvaluator(
            units,
            registry.digest()
        );
        var decimal = intervalDecimalPolicy();
        for (var step : input.steps()) {
            if (!"filter".equals(step.method())) continue;
            var fields = new java.util.LinkedHashMap<UUID, com.voltcanary.vcl.VclType>();
            step.fields().forEach((id, type) -> fields.put(UUID.fromString(id), type));
            var predicate = compiler.compile(
                step.config().path("predicate").toString(),
                fields,
                owner
            );
            filters.put(step.id(), row ->
                evaluator.evaluate(
                    predicate,
                    row.fields(),
                    decimal,
                    compilation.accessScopeDigest()
                )
            );
        }
        return Map.copyOf(filters);
    }

    static List<VclIntervalUnionPlan.Row> expandIntervalInput(
        RulePreparedLookups prepared,
        List<VclCompilation.Node> steps,
        VclSustainedPlan.Sample sample,
        int limit,
        StringBuilder fingerprints,
        Map<String, java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>> filters
    ) {
        List<VclIntervalUnionPlan.Row> expanded = List.of(
            new VclIntervalUnionPlan.Row(sample.id(), sample.fields())
        );
        for (var step : steps) {
            var projected = new ArrayList<VclIntervalUnionPlan.Row>();
            for (var row : expanded) {
                var filter = filters.get(step.id());
                if (filter == null) projectIntervalLookup(
                    prepared,
                    step,
                    row,
                    sample.at(),
                    projected,
                    fingerprints,
                    limit
                );
                else {
                    var value = filter.apply(row);
                    if (value.isFailure()) throw new VclException(value.reason(), "");
                    if (value.status() == ScalarValue.Status.OK && !value.isNull()) {
                        if (value.literal().path("value").booleanValue()) projected.add(row);
                    } else {
                        projected.add(
                            new VclIntervalUnionPlan.Row(
                                row.observation(),
                                row.fields(),
                                ScalarValue.unknown(
                                    "INTERVAL_FILTER_UNKNOWN",
                                    List.of(row.observation())
                                )
                            )
                        );
                    }
                }
                if (projected.size() > limit) throw new VclException("INTERVAL_INPUT_LIMIT", "");
            }
            expanded = projected;
        }
        return expanded;
    }

    static void projectIntervalLookup(
        RulePreparedLookups prepared,
        VclCompilation.Node lookup,
        VclIntervalUnionPlan.Row row,
        Instant eventTime,
        List<VclIntervalUnionPlan.Row> projected,
        StringBuilder fingerprints,
        int limit
    ) {
        var wire = new java.util.LinkedHashMap<UUID, JsonNode>();
        row.fields().forEach((id, value) -> {
            if (value.status() == ScalarValue.Status.OK) wire.put(id, value.literal());
        });
        JsonNode result;
        try {
            result = prepared.lookup(lookup.id(), wire, eventTime);
        } catch (VclException unresolved) {
            if (
                !"ASOF_KEY_UNKNOWN".equals(unresolved.code()) &&
                !"ASOF_TIME_INVALID".equals(unresolved.code())
            ) throw unresolved;
            projected.add(mergeIntervalLookup(lookup, row, null, unresolved.code()));
            return;
        }
        fingerprints.append('\n').append(VclCanonical.sha256(VclCanonical.encode(result)));
        if (!result.has("segments")) {
            projected.add(mergeIntervalLookup(lookup, row, result, null));
            return;
        }
        var temporal = lookup.config().path("temporal");
        UUID start = UUID.fromString(temporal.path("startFieldId").textValue());
        UUID end = UUID.fromString(temporal.path("endFieldId").textValue());
        var segments = result.path("segments");
        if (segments.size() > limit - projected.size()) throw new VclException(
            "INTERVAL_INPUT_LIMIT",
            ""
        );
        for (var segment : segments) {
            String identity = String.join(
                "\n",
                row.observation().toString(),
                lookup.id(),
                segment.path("start").textValue(),
                segment.path("end").textValue()
            );
            UUID id = UUID.nameUUIDFromBytes(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            var fields = new java.util.LinkedHashMap<>(row.fields());
            fields.put(start, intervalInstant(segment.path("start").textValue()));
            fields.put(end, intervalInstant(segment.path("end").textValue()));
            projected.add(
                mergeIntervalLookup(
                    lookup,
                    new VclIntervalUnionPlan.Row(id, fields, row.eligibility()),
                    segment,
                    null
                )
            );
        }
    }

    static VclIntervalUnionPlan.Row mergeIntervalLookup(
        VclCompilation.Node lookup,
        VclIntervalUnionPlan.Row row,
        JsonNode outcome,
        String unresolved
    ) {
        var fields = new java.util.LinkedHashMap<>(row.fields());
        for (var field : lookup.config().path("select")) {
            UUID id = UUID.fromString(field.textValue());
            var literal =
                outcome == null ? null : outcome.path("row").path("values").get(field.textValue());
            ScalarValue value;
            if (outcome == null || "UNKNOWN".equals(outcome.path("status").textValue())) {
                value = ScalarValue.unknown(
                    unresolved == null ? outcome.path("reason").textValue() : unresolved,
                    List.of(row.observation())
                );
            } else {
                if (literal == null) throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                value = ScalarValue.known(RuleCompilationSnapshot.PARSER, literal.toString());
                var expected = lookup.fields().get(field.textValue());
                if (
                    !value.isNull() &&
                    (!expected.kind().name().equals(literal.path("type").textValue()) ||
                        (expected.kind() == com.voltcanary.vcl.VclType.Kind.DECIMAL &&
                            !expected.unit().equals(literal.path("unit").textValue())))
                ) {
                    throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                }
            }
            var previous = fields.putIfAbsent(id, value);
            if (
                previous != null &&
                (previous.status() != ScalarValue.Status.OK ||
                    value.status() != ScalarValue.Status.OK ||
                    !previous.literal().equals(value.literal()))
            ) {
                fields.put(
                    id,
                    ScalarValue.unknown("ASOF_FIELD_CONFLICT", List.of(row.observation()))
                );
            }
        }
        return new VclIntervalUnionPlan.Row(row.observation(), fields, row.eligibility());
    }

    static ScalarValue intervalInstant(String value) {
        return ScalarValue.known(
            RuleCompilationSnapshot.PARSER,
            JsonNodeFactory.instance
                .objectNode()
                .put("type", "INSTANT")
                .put("value", value)
                .toString()
        );
    }
}
