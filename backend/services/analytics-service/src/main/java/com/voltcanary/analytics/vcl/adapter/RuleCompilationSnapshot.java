package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.CompilationOwnerValidation.ResourcePin;
import com.voltcanary.analytics.vcl.DurableSustained;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.VclIntervalUnionPlan;
import com.voltcanary.analytics.vcl.VclIntervalUnionKernel;
import com.voltcanary.analytics.vcl.ScalarDecimalPolicy;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.SpatialFeatureSnapshot;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel;
import com.voltcanary.analytics.vcl.VclSessionAbsenceKernel;
import com.voltcanary.analytics.vcl.VclJournalInputPlan;
import com.voltcanary.analytics.vcl.VclSustainedPlan;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.analytics.vcl.VclWindowAggregateKernel;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.VclZoneSessionKernel;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceCoverage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Document-bound static metadata composition, deliberately distinct from OWNER_VALIDATED_IR. */
public final class RuleCompilationSnapshot {
    private static final VclParser PARSER = new VclParser();
    static final long MAX_RETAINED_WINDOW_BYTES = 8L * 1024 * 1024;
    private final VclDocument document;
    private final SourceBindingSnapshot bindings;
    private final Map<SourceBindingSnapshots.Target, SourceCaptureSnapshot> journals;
    private final NativeDatasetSnapshot datasets;
    private final AutomationGrantSnapshot grant;
    private final EvidencePolicySnapshot policy;
    private final List<SourceBindingSnapshots.Target> bindingTargets;
    private final List<NativeDatasetSnapshots.Target> datasetTargets;
    private final ResourcePin grantPin;
    private final ResourcePin policyPin;
    private final String ownerScope;
    private final String scope;

    RuleCompilationSnapshot(VclDocument document, SourceBindingSnapshot bindings, NativeDatasetSnapshot datasets,
            AutomationGrantSnapshot grant, EvidencePolicySnapshot policy,
            List<SourceBindingSnapshots.Target> bindingTargets, List<NativeDatasetSnapshots.Target> datasetTargets,
            Map<SourceBindingSnapshots.Target, SourceCaptureSnapshot> journals) {
        this.document = document;
        this.bindings = bindings;
        this.journals = Map.copyOf(journals);
        this.datasets = datasets;
        this.grant = grant;
        this.policy = policy;
        this.bindingTargets = List.copyOf(bindingTargets);
        this.datasetTargets = List.copyOf(datasetTargets);
        grantPin = grant.pin();
        policyPin = policy.pin();
        ownerScope = journals.isEmpty() ? bindings.accessScopeDigest() : journals.values().iterator().next().accessScopeDigest();
        String ownerPins = ownerScope + "\n" + grantPin.snapshotDigest() + "\n" + policyPin.snapshotDigest();
        scope = VclCanonical.sha256(journals.isEmpty() ? "RULE_STATIC_OWNERS/1\n" + ownerPins
                : "RULE_JOURNAL_OWNERS/1\n" + ownerPins + "\n"
                        + String.join("\n", journals.values().stream().map(SourceCaptureSnapshot::cutDigest).sorted().toList()));
        reauthorize();
    }

    /** The compiler sees the same closed schema/registry as other consumers, backed by real owner APIs. */
    public VclCompilation compile(VclCompiler compiler) {
        var compilation = compiler.compile(document, new Metadata());
        reauthorize();
        return compilation;
    }

    public com.voltcanary.vcl.VclSourceCoverage coverage(UUID binding, long revision, UUID proof, Instant from, Instant until) {
        var journal = journals.get(new SourceBindingSnapshots.Target(binding, revision));
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        new Metadata().binding(binding, revision).orElseThrow(() -> new VclException("SOURCE_BINDING_UNAVAILABLE", ""));
        var result = journal.coverage(proof, from, until);
        reauthorize();
        return result;
    }

    public record SourceStartProof(UUID binding, long schemaRevision, UUID proofId) { }

    public record SourceStartCheckpoint(Instant from, Instant boundary,
            List<SourceCaptureSnapshot.CoveredCheckpoint> sources, String digest) {
        public SourceStartCheckpoint {
            sources = List.copyOf(sources);
        }
    }

    /** Build the source part of a start checkpoint from the exact journal cuts. */
    public SourceStartCheckpoint sourceStartCheckpoint(Instant from, Instant boundary, List<SourceStartProof> proofs) {
        if (from == null || boundary == null || !from.isBefore(boundary)
                || from.getNano() % 1000 != 0 || boundary.getNano() % 1000 != 0
                || journals.isEmpty() || proofs == null || proofs.size() != journals.size()) {
            throw new VclException("SOURCE_START_CHECKPOINT_INVALID", "");
        }
        var selections = new java.util.HashMap<SourceBindingSnapshots.Target, UUID>();
        for (var proof : proofs) {
            if (proof == null || proof.binding() == null || proof.proofId() == null
                    || selections.putIfAbsent(new SourceBindingSnapshots.Target(proof.binding(), proof.schemaRevision()),
                            proof.proofId()) != null) {
                throw new VclException("SOURCE_START_CHECKPOINT_INVALID", "");
            }
        }
        if (!selections.keySet().equals(journals.keySet())) {
            throw new VclException("SOURCE_START_CHECKPOINT_INVALID", "");
        }
        reauthorize();
        var entries = journals.entrySet().stream().sorted(Map.Entry.comparingByKey(
                java.util.Comparator.comparing(SourceBindingSnapshots.Target::id)
                        .thenComparingLong(SourceBindingSnapshots.Target::revision))).toList();
        var checkpoints = new ArrayList<SourceCaptureSnapshot.CoveredCheckpoint>();
        var payload = JsonNodeFactory.instance.objectNode().put("profile", "RULE_SOURCE_START/1")
                .put("scope", scope).put("documentDigest", VclCanonical.sha256(VclCanonical.encode(document.json())))
                .put("from", from.toString()).put("boundary", boundary.toString());
        var sourcePins = payload.putArray("sources");
        for (var entry : entries) {
            var checkpoint = entry.getValue().coveredCheckpoint(selections.get(entry.getKey()), from, boundary);
            checkpoints.add(checkpoint);
            var covered = checkpoint.proof();
            var evidence = covered.evidence();
            var pin = sourcePins.addObject().put("binding", checkpoint.binding().toString())
                    .put("revision", checkpoint.schemaRevision()).put("cut", checkpoint.cut().digest())
                    .put("head", checkpoint.cut().head()).put("proof", covered.proofId().toString())
                    .put("snapshot", covered.snapshotId().toString()).put("generation", covered.inputGeneration().toString())
                    .put("requiredCheckpoint", covered.requiredCheckpoint())
                    .put("producerEpoch", evidence.position().epoch().toString()).put("sequence", evidence.position().sequence())
                    .put("coveredFrom", evidence.from().toString()).put("coveredUntil", evidence.until().toString());
            if (covered.acceptedAt() != null) pin.put("acceptedAt", covered.acceptedAt().toString());
            if (evidence.eventTimeBasis() != null) pin.put("eventTimeBasis", evidence.eventTimeBasis());
            if (!evidence.parts().isEmpty()) {
                var parts = pin.putArray("coverageParts");
                evidence.parts().forEach(id -> parts.add(id.toString()));
            }
        }
        reauthorize();
        return new SourceStartCheckpoint(from, boundary, checkpoints, VclCanonical.sha256(VclCanonical.encode(payload)));
    }

    public void verifySourceStartCheckpoint(SourceStartCheckpoint expected) {
        if (expected == null) throw new VclException("SOURCE_START_CHECKPOINT_INVALID", "");
        var proofs = expected.sources().stream().map(source ->
                new SourceStartProof(source.binding(), source.schemaRevision(), source.proof().proofId())).toList();
        if (!expected.equals(sourceStartCheckpoint(expected.from(), expected.boundary(), proofs))) {
            throw new VclException("SOURCE_START_CHECKPOINT_CHANGED", "");
        }
    }

    void verifyCompilationStart(RuleCompilationSnapshots.PreparedStart prepared) {
        verifySourceStartCheckpoint(prepared.checkpoint().source());
        if (prepared.lookups() != null) prepared.lookups().verifyManifests();
    }

    public record LookupSelection(VclDocument query, UUID intervalField) { }

    public record ReferenceStartPin(String canonicalQuery, UUID intervalField, String manifestDigest) { }

    private record LookupPin(LookupSelection selection, JsonNode manifest) { }

    public final class PreparedLookups {
        private final VclCompilation compilation;
        private final Map<String, LookupPin> pins;
        private final NativeAsOfOwner owner;
        private final NativeAsOfOwner.Transport transport;

        private PreparedLookups(VclCompilation compilation, Map<String, LookupPin> pins, NativeAsOfOwner owner,
                NativeAsOfOwner.Transport transport) {
            this.compilation = compilation;
            this.pins = Map.copyOf(pins);
            this.owner = owner;
            this.transport = transport;
        }

        public VclCompilation compilation() { return compilation; }

        Map<String, ReferenceStartPin> referenceStartPins() {
            var result = new java.util.TreeMap<String, ReferenceStartPin>();
            pins.forEach((node, pin) -> result.put(node, new ReferenceStartPin(
                    VclCanonical.encode(pin.selection().query().json()), pin.selection().intervalField(),
                    VclCanonical.sha256(VclCanonical.encode(pin.manifest())))));
            return Map.copyOf(result);
        }

        public JsonNode lookup(String nodeId, Map<UUID, JsonNode> event, Instant eventTime) {
            var pin = pins.get(nodeId);
            if (pin == null) throw new VclException("ASOF_OPERATOR_REQUIRED", "");
            var node = compilation.graph().stream().filter(candidate -> candidate.id().equals(nodeId)).findFirst().orElseThrow();
            reauthorize(true);
            var result = owner.lookup(transport, node, pin.selection().query(), pin.selection().intervalField(),
                    event, eventTime, pin.manifest(), referenceReadDeadline());
            reauthorize(true);
            return result;
        }

        public WindowPreview previewIntervalUnion(String nodeId, String subjectDigest, VclWindowPlan.Window window,
                long byteBudget) {
            return previewJournalIntervalUnion(compilation, metadata(), this, nodeId, subjectDigest, window, byteBudget,
                    null, null, window.endExclusive());
        }

        public WindowPreview previewWindowAggregate(String nodeId, String subject, VclWindowPlan.Window window,
                long byteBudget) {
            return journalAggregate(nodeId).preview(subject, window, byteBudget, true);
        }

        WindowPreview previewWindowAggregate(String nodeId, ScalarValue subject, VclWindowPlan.Window window, long byteBudget) {
            return journalAggregate(nodeId).preview(subject, window, byteBudget);
        }

        String sourceCutDigest() {
            return RuleCompilationSnapshot.this.sourceCutDigest();
        }

        CorrectionCatchup correctionCatchup(String nodeId, SourceBindingSnapshots.Target target,
                VclWindowPlan.Window window, String originalCheckpoint, boolean aggregate) {
            var source = journals.get(target);
            if (source == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
            var latest = source.atCurrentHead();
            var until = window.endExclusive();
            if (!aggregate) {
                var plan = VclIntervalUnionPlan.compile(compilation, nodeId, metadata());
                var definition = latest.binding(target.id(), target.revision())
                        .orElseThrow(() -> new VclException("SOURCE_CAPTURE_UNAVAILABLE", "")).definition();
                until = plan.reportingHorizon(window, definition);
            }
            var timing = VclTimingPlan.compile(compilation);
            long lastRelevant = VclObservationReadiness.checkpoint(originalCheckpoint);
            long remainingBytes = MAX_RETAINED_WINDOW_BYTES;
            String after = originalCheckpoint;
            VclObservationPage page;
            do {
                page = latest.readPage(after, 128, window.startInclusive(), until);
                remainingBytes -= page.bytes().length;
                if (remainingBytes < 0) throw new VclException("WINDOW_INPUT_BYTE_BUDGET_EXCEEDED", "");
                for (var event : page.observations()) {
                    var at = Instant.parse(event.path("eventTime").textValue());
                    var recorded = Instant.parse(event.path("recordedAt").textValue());
                    if (timing.classify(at, recorded) != VclTimingPlan.Admission.QUARANTINED_FUTURE) {
                        lastRelevant = VclObservationReadiness.checkpoint(event.path("checkpoint").textValue());
                    }
                }
                after = page.nextCheckpoint();
            } while (page.hasMore());
            return new CorrectionCatchup(latest.inputGeneration(),
                    VclObservationReadiness.checkpoint(latest.capturedHeadCheckpoint()), lastRelevant);
        }

        JournalWindowBatch journalAggregate(String nodeId) {
            return prepareJournalWindowBatch(compilation, metadata(), this, nodeId,
                    64L * VclWindowAggregateKernel.MAX_INPUTS, true);
        }

        Optional<IntervalFinalization> finalizeIntervalUnion(String nodeId, String subjectDigest, VclWindowPlan.Window window,
                Map<String, ReferenceStartPin> expected, long minimumCheckpoint,
                java.util.function.BiFunction<Instant, Instant, UUID> discoverProof) {
            if (!referenceStartPins().equals(expected)) throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
            return finalizeJournalIntervalUnion(compilation, metadata(), this, nodeId, subjectDigest, window,
                    window.endExclusive(), minimumCheckpoint, discoverProof);
        }

        Optional<IntervalFinalization> proveIntervalPrefix(String nodeId, String subjectDigest, VclWindowPlan.Window window,
                Instant through, Map<String, ReferenceStartPin> expected, long minimumCheckpoint,
                java.util.function.BiFunction<Instant, Instant, UUID> discoverProof) {
            if (!referenceStartPins().equals(expected)) throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
            requireIntervalLowerBound(compilation, nodeId, window, through);
            return finalizeJournalIntervalUnion(compilation, metadata(), this, nodeId, subjectDigest, window, through,
                    minimumCheckpoint, discoverProof);
        }

        void stageIntervalWindows(String nodeId, SourceBindingSnapshots.Target target, JdbcRuleDeployments.Deployment deployment,
                JdbcKeyedStateStore store, JsonNode event, UUID holder) {
            RuleCompilationSnapshot.this.stageIntervalWindows(compilation, metadata(), this, nodeId, target,
                    deployment, store, event, holder);
        }

        String intervalPlanDigest(String nodeId) {
            return VclIntervalUnionPlan.compile(compilation, nodeId, metadata()).digest();
        }

        String intervalEvaluatorPlanDigest(String nodeId, String bundleDigest) {
            return RuleCompilationSnapshot.this.intervalEvaluatorPlanDigest(compilation, metadata(), this, nodeId, bundleDigest);
        }

        JournalWindowDecision intervalDecision(String nodeId, ScalarValue subject) {
            return JournalWindowDecision.compileTyped(compilation, intervalOperator(compilation, nodeId),
                    intervalDecimalPolicy(), metadata(), subject);
        }

        private Metadata metadata() {
            var datasets = new HashSet<UUID>();
            pins.values().forEach(pin -> datasets.add(UUID.fromString(
                    pin.selection().query().spec().at("/target/datasetId").textValue())));
            return new Metadata(datasets, compilation.accessScopeDigest());
        }

        private void verifyManifests() {
            long deadline = referenceReadDeadline();
            for (var node : compilation.graph()) {
                var pin = pins.get(node.id());
                if (pin == null) continue;
                var keys = new ArrayList<UUID>();
                node.config().path("keyPairs").forEach(pair -> keys.add(UUID.fromString(pair.path("recordFieldId").textValue())));
                var current = owner.inspect(transport, pin.selection().query(), pin.selection().intervalField(), keys, deadline);
                if (!pin.manifest().equals(current)) throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
            }
            reauthorize(true);
        }
    }

    record CorrectionCatchup(UUID generation, long scannedHead, long lastRelevant) { }

    private static long referenceReadDeadline() {
        return System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
    }

    /** Compile a bounded historical rule read with an owner-verified sealed query for each lookup node. */
    PreparedLookups prepareLookups(VclCompiler compiler, NativeAsOfOwner owner, String authorization,
            Map<String, LookupSelection> selections) {
        return prepareLookups(compiler, owner, owner.user(authorization), selections);
    }

    PreparedLookups prepareLookups(VclCompiler compiler, NativeAsOfOwner owner, NativeAsOfOwner.Transport transport,
            Map<String, LookupSelection> selections) {
        var pins = new java.util.TreeMap<String, LookupPin>();
        var preparedDatasets = new HashSet<UUID>();
        long deadline = referenceReadDeadline();
        reauthorize();
        for (var declaration : document.spec().path("nodes")) {
            if (!"lookup_asof".equals(declaration.path("method").textValue())) continue;
            String nodeId = declaration.path("id").textValue();
            var selection = selections.get(nodeId);
            if (selection == null || datasets == null) throw new VclException("ASOF_SNAPSHOT_REQUIRED", "");
            var config = declaration.path("config");
            var target = config.path("target");
            UUID dataset = UUID.fromString(target.path("datasetId").textValue());
            long revision = target.path("schemaRevision").longValue();
            var query = selection.query().spec();
            if (!target.equals(query.path("target"))) throw new VclException("ASOF_PROJECTION_CONFLICT", "");
            var keys = new ArrayList<UUID>();
            config.path("keyPairs").forEach(pair -> keys.add(UUID.fromString(pair.path("recordFieldId").textValue())));
            var expected = new HashSet<>(keys);
            expected.add(selection.intervalField());
            config.path("select").forEach(field -> expected.add(UUID.fromString(field.textValue())));
            var selected = new HashSet<UUID>();
            query.path("select").forEach(field -> selected.add(UUID.fromString(field.textValue())));
            if (!expected.equals(selected)) throw new VclException("ASOF_PROJECTION_CONFLICT", "");
            grant.requireFields(VclMetadata.Resource.DATASET, dataset, revision, List.copyOf(selected));
            var description = datasets.description(dataset, revision).orElseThrow(() -> new VclException("DATASET_UNAVAILABLE", ""));
            var manifest = owner.inspect(transport, selection.query(), selection.intervalField(), keys, deadline);
            if (!description.schemaDigest().equals(manifest.path("schemaDigest").textValue())
                    || !ownerScope.equals(manifest.path("accessScopeDigest").textValue())) {
                throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
            }
            if (pins.putIfAbsent(nodeId, new LookupPin(selection, manifest)) != null) throw new VclException("ASOF_PROJECTION_CONFLICT", "");
            preparedDatasets.add(dataset);
        }
        if (pins.isEmpty() || !pins.keySet().equals(selections.keySet())) throw new VclException("ASOF_OPERATOR_REQUIRED", "");
        var manifestSet = JsonNodeFactory.instance.objectNode();
        pins.forEach((id, pin) -> manifestSet.putObject(id).put("intervalField", pin.selection().intervalField().toString())
                .set("manifest", pin.manifest()));
        String lookupScope = VclCanonical.sha256(scope + "\nRULE_ASOF/1\n" + VclCanonical.encode(manifestSet));
        var compiled = compiler.compile(document, new Metadata(preparedDatasets, lookupScope));
        reauthorize(true);
        return new PreparedLookups(compiled, pins, owner, transport);
    }

    /** Verify static types and exact grants before checking the source owner's runtime readiness. */
    public VclCompilation compileWithReadySources(VclCompiler compiler) {
        var compilation = compile(compiler);
        if (journals.isEmpty()) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        for (var journal : journals.values()) {
            journal.requireRuntimeReady();
        }
        reauthorize();
        return compilation;
    }

    public final class JournalWindowBatch {
        private final VclWindowAggregateKernel.Plan plan;
        private final long maximumMemberships;
        private final SourceCaptureSnapshot journal;
        private final VclTimingPlan timing;
        private final String subjectField;
        private final String valueField;
        private final VclCompilation compilation;
        private final VclCompilation.Node aggregateNode;
        private final ScalarDecimalPolicy decimalPolicy;
        private final IntervalInput input;
        private final Map<String, java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>> filters;
        private final PreparedLookups prepared;
        private final Metadata owner;

        private JournalWindowBatch(VclWindowAggregateKernel.Plan plan, long maximumMemberships, SourceCaptureSnapshot journal,
                VclTimingPlan timing, String subjectField, String valueField, VclCompilation compilation,
                VclCompilation.Node aggregateNode, ScalarDecimalPolicy decimalPolicy, IntervalInput input,
                PreparedLookups prepared, Metadata owner) {
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
            this.filters = intervalFilters(compilation, input, owner);
        }

        public VclWindowAggregateKernel.Plan plan() { return plan; }
        public long maximumMemberships() { return maximumMemberships; }

        record AggregateRoute(ScalarValue subject, List<VclWindowPlan.Window> windows) { }

        AggregateRoute route(JsonNode observation) {
            var eventTime = Instant.parse(observation.path("eventTime").textValue());
            var recordedAt = Instant.parse(observation.path("recordedAt").textValue());
            if (timing.classify(eventTime, recordedAt) == VclTimingPlan.Admission.QUARANTINED_FUTURE) {
                return new AggregateRoute(null, List.of());
            }
            ScalarValue subject;
            if (prepared != null) {
                UUID id = UUID.fromString(observation.path("inputEventId").textValue());
                var rows = expandIntervalInput(prepared, input.steps(), sustainedSample(observation, id, eventTime),
                        1, new StringBuilder(), filters);
                if (rows.isEmpty()) return new AggregateRoute(null, List.of());
                subject = rows.getFirst().fields().get(UUID.fromString(subjectField));
            } else {
                var identity = observationField(observation, subjectField);
                if (!filters.isEmpty() && filterWindowObservation(observation, eventTime).isEmpty()) {
                    return new AggregateRoute(null, List.of());
                }
                subject = observationValue(identity, UUID.fromString(observation.path("inputEventId").textValue()));
            }
            if (subject != null && subject.isFailure()) throw new VclException(subject.reason(), "");
            var windows = plan.windows().assign(eventTime).stream().distinct().toList();
            return new AggregateRoute(subject == null || subject.status() != ScalarValue.Status.OK || subject.isNull()
                    ? null : subject, windows);
        }

        private List<VclIntervalUnionPlan.Row> filterWindowObservation(JsonNode observation, Instant eventTime) {
            UUID id = UUID.fromString(observation.path("inputEventId").textValue());
            return expandIntervalInput(null, input.steps(), sustainedSample(observation, id, eventTime),
                    1, new StringBuilder(), filters);
        }

        String evaluatorPlanDigest() {
            Instant end = Instant.EPOCH.plus(plan.windows().maximumSpanUs(), java.time.temporal.ChronoUnit.MICROS);
            if (timing.windowCloseDueAt(end).isAfter(Instant.EPOCH.plus(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL))) {
                throw new VclException("WINDOW_COVERAGE_PROFILE_UNAVAILABLE", "");
            }
            var decision = JournalWindowDecision.compileShape(compilation, aggregateNode, decimalPolicy, owner);
            var terminal = compilation.graph().stream().filter(node -> node.id().equals(decision.terminalId()))
                    .findFirst().orElseThrow();
            if (!"RULE_SUBJECT_OCCURRENCE".equals(terminal.config().path("dedup").textValue())) {
                throw new VclException("WINDOW_ALERT_DEDUP_UNSUPPORTED", "");
            }
            return VclCanonical.sha256(String.join("\n", "DIRECT_JOURNAL_WINDOW_EVALUATOR/1",
                    plan.digest(), timing.digest(), decision.digest()));
        }

        public DurableJournalWindow durable(com.voltcanary.analytics.vcl.KeyedStateStore store, UUID executionEpoch,
                String subject, VclWindowPlan.Window window, UUID coverageProof) {
            DurableJournalWindow.Commit commit = store instanceof JdbcKeyedStateStore jdbc
                    ? (key, lease, transition, watermark) -> jdbc.commitJournalWindow(
                            key, lease, transition, window.startInclusive(), null, watermark)
                    : (key, lease, transition, watermark) -> store.commit(key, lease, transition);
            return durable(store, executionEpoch, subject, window, coverageProof, () -> {}, commit, null);
        }

        DurableJournalWindow durable(com.voltcanary.analytics.vcl.KeyedStateStore store, UUID executionEpoch,
                String subject, VclWindowPlan.Window window, UUID coverageProof, Runnable deploymentCheck,
                DurableJournalWindow.Commit commit, String reviewedBundleDigest) {
            return durable(store, executionEpoch, subject, window, coverageProof, deploymentCheck, commit,
                    reviewedBundleDigest, () -> null);
        }

        Instant closeDueAt(VclWindowPlan.Window window) {
            return timing.windowCloseDueAt(window.endExclusive());
        }

        record CoveredAggregate(Instant through, SourceCaptureSnapshot.Cut cut, String inputDigest,
                VclSourceCoverage proof, ScalarValue value, JsonNode decision) { }

        boolean usesCountPrefixes() {
            var config = aggregateNode.config();
            return "COUNT".equals(config.path("reducer").textValue())
                    && "LOWER_BOUND_PROVEN".equals(config.path("emit").textValue());
        }

        void stageAggregateWindow(JdbcKeyedStateStore store, JdbcRuleDeployments.Deployment deployment,
                ScalarValue scalar, VclWindowPlan.Window window, long checkpoint, JsonNode event, UUID holder) {
            var key = new KeyedStateStore.Key(deployment.account(), deployment.rule(), deployment.epoch(),
                    aggregateSubjectDigest(scalar), IntervalWindowSchedule.occurrence(window));
            var recipe = new IntervalWindowSchedule.Recipe(aggregateNode.id(), evaluatorPlanDigest(), journal.inputGeneration(),
                    window, closeDueAt(window), VclCanonical.encode(scalar.literal()),
                    usesCountPrefixes() && java.time.temporal.ChronoUnit.MICROS.between(
                            window.startInclusive(), window.endExclusive()) >= 16 ? 1 : 0, null, 0, null);
            owner.reauthorizeInputs();
            var route = prepared == null ? null : IntervalWindowSchedule.Route.from(
                    UUID.fromString(input.source().config().path("bindingId").textValue()), event, owner::reauthorizeInputs);
            IntervalWindowSchedule.stage(store, deployment, key, recipe, checkpoint, holder, route);
        }

        String aggregateSubjectDigest(ScalarValue subject) {
            return usesCountPrefixes() || !"TEXT".equals(subject.literal().path("type").textValue())
                    ? VclWindowSessionKernel.identity(subject)
                    : VclCanonical.sha256(subject.literal().path("value").textValue());
        }

        JdbcJournalWatermarks.Candidate coveredFrontier(KeyedStateStore.Key key, VclWindowPlan.Window window,
                Instant through, VclSourceCoverage proof, String evidenceDigest) {
            var dependency = timing.requiredInputs().stream().filter(dep -> dep.kind() == VclMetadata.Resource.BINDING)
                    .findFirst().orElseThrow(() -> new VclException("WINDOW_FRONTIER_PENDING", ""));
            var evidence = proof.evidence();
            var frontierAt = through;
            if (prepared == null) {
                var frontier = timing.watermark(List.of(new VclTimingPlan.Progress(dependency, evidence.until(), proof.acceptedAt())),
                        timing.digest(), compilation.accessScopeDigest());
                if (frontier.status() != ScalarValue.Status.OK) throw new VclException("WINDOW_FRONTIER_PENDING", "");
                frontierAt = Instant.parse(frontier.literal().path("value").textValue());
            }
            String scope = VclCanonical.sha256(String.join("\n", "RULE_JOURNAL_WATERMARK/1", key.ruleId().toString(),
                    progressScope(), dependency.id().toString(), Long.toString(dependency.revision()),
                    proof.snapshotId().toString(), proof.inputGeneration().toString(), evidence.position().epoch().toString(),
                    VclSourceCoverage.SOURCE_EVENT_TIME));
            return new JdbcJournalWatermarks.Candidate(scope, evidence.from(), evidence.until(),
                    frontierAt, evidenceDigest,
                    new VclWindowPlan.Window(window.startInclusive(), through));
        }

        private String progressScope() {
            return VclCanonical.sha256(String.join("\n", VclTimingPlan.VERSION,
                    compilation.documentDigest(), compilation.registryDigest(), compilation.unitRegistryDigest(),
                    ownerScope, grantPin.snapshotDigest(), policyPin.snapshotDigest()));
        }

        Optional<CoveredAggregate> evaluateCovered(ScalarValue subject, VclWindowPlan.Window window, Instant through, long minimumCheckpoint,
                java.util.function.BiFunction<Instant, Instant, UUID> discoverProof) {
            boolean text = "TEXT".equals(subject.literal().path("type").textValue());
            validateWindow(text ? subject.literal().path("value").textValue() : "", window);
            boolean lowerBound = through.isBefore(window.endExclusive());
            if (!through.isAfter(window.startInclusive()) || through.isAfter(window.endExclusive())
                    || (lowerBound && !usesCountPrefixes())) {
                throw new VclException("WINDOW_LOWER_BOUND_UNAVAILABLE", "");
            }
            var prefix = new VclWindowPlan.Window(window.startInclusive(), through);
            var due = timing.windowCloseDueAt(through);
            UUID proofId = discoverProof.apply(prefix.startInclusive(), due);
            if (proofId == null) return Optional.empty();
            var base = minimumCheckpoint > VclObservationReadiness.checkpoint(journal.capturedHeadCheckpoint())
                    ? journal.atCheckpoint(Long.toString(minimumCheckpoint)) : journal;
            var proof = base.coverage(proofId, prefix.startInclusive(), due, true);
            if (!proof.qualifiesEventTime() || timing.classify(proof.evidence().until(), proof.acceptedAt())
                    == VclTimingPlan.Admission.QUARANTINED_FUTURE) return Optional.empty();
            var covered = base.forFinalization(proof);
            var retained = readRetainedInput(covered, MAX_RETAINED_WINDOW_BYTES, prefix);
            // The reducer still checks the assigned calendar or rolling window. Only the supplied input is clipped.
            var evaluated = reduceWindow(retained, subject, window, lowerBound);
            var predicate = JournalWindowDecision.compileTyped(compilation, aggregateNode, decimalPolicy, owner, subject);
            var decision = lowerBound ? predicate.evaluateLowerBound(evaluated.localValue()) : predicate.evaluate(evaluated.localValue());
            if (!proof.equals(covered.coverage(proofId, prefix.startInclusive(), due))) {
                throw new VclException("WINDOW_COVERAGE_CHANGED", "");
            }
            owner.reauthorizeInputs();
            return Optional.of(new CoveredAggregate(through, covered.cut(), evaluated.inputDigest(), proof,
                    evaluated.localValue(), decision));
        }

        DurableJournalWindow durable(com.voltcanary.analytics.vcl.KeyedStateStore store, UUID executionEpoch,
                String subject, VclWindowPlan.Window window, UUID coverageProof, Runnable deploymentCheck,
                DurableJournalWindow.Commit commit, String reviewedBundleDigest, java.util.function.Supplier<UUID> discoverProof) {
            boolean requireFinalization = reviewedBundleDigest != null;
            validateWindow(subject, window);
            UUID rule = UUID.fromString(document.spec().path("ruleId").textValue());
            var decision = JournalWindowDecision.compile(compilation, aggregateNode, decimalPolicy, new Metadata(), subject);
            DurableJournalWindow.AlertIntent alertIntent = null;
            if (requireFinalization) {
                var terminal = compilation.graph().stream().filter(node -> node.id().equals(decision.terminalId()))
                        .findFirst().orElseThrow();
                if (!"RULE_SUBJECT_OCCURRENCE".equals(terminal.config().path("dedup").textValue())) {
                    throw new VclException("WINDOW_ALERT_DEDUP_UNSUPPORTED", "");
                }
                alertIntent = new DurableJournalWindow.AlertIntent(reviewedBundleDigest, grantPin.id(), grantPin.revision(),
                        grantPin.snapshotDigest(), policyPin.id(), policyPin.snapshotDigest(), terminal.config().path("severity").textValue());
            }
            var source = input.source().config();
            var recipe = new DurableJournalWindow.Recipe(compilation.canonicalDocument(), aggregateNode.id(),
                    UUID.fromString(source.path("bindingId").textValue()), source.path("schemaRevision").longValue(),
                    journal.cut(), maximumMemberships, subject, window, coverageProof);
            String progressScope = progressScope();
            return new DurableJournalWindow(store, journal.accountId(), rule, executionEpoch, subject, window,
                    coverageProof, journal.cutDigest(), plan.digest(), timing, compilation.accessScopeDigest(),
                    progressScope, decision.digest(),
                    closeDueAt(window), byteBudget -> preview(subject, window, byteBudget, requireFinalization), decision, discoverProof,
                    proof -> journal.coverage(proof, window.startInclusive(), window.endExclusive(), requireFinalization),
                    proof -> {
                        var finalCut = journal.forFinalization(proof);
                        var retained = readRetainedInput(finalCut, MAX_RETAINED_WINDOW_BYTES, window);
                        return new DurableJournalWindow.FinalInput(finalCut.cut(), retained.inputDigest(),
                                reduceWindow(retained, textSubject(subject), window, false).localValue());
                    },
                    () -> {
                        reauthorize();
                        deploymentCheck.run();
                    }, recipe, commit, alertIntent);
        }

        /** Read the plan's original source cut under fresh ownership and the original field grant. */
        public VclObservationPage readPage(String afterCheckpoint, int limit) {
            reauthorize();
            var page = journal.readPage(afterCheckpoint, limit);
            reauthorize();
            return page;
        }

        /** Materialize the complete retained generation prefix within row and encoded-byte bounds. */
        public RetainedWindowInput readRetainedInput(long encodedByteBudget) {
            return readRetainedInput(journal, encodedByteBudget, null);
        }

        private RetainedWindowInput readRetainedInput(SourceCaptureSnapshot input, long encodedByteBudget,
                VclWindowPlan.Window range) {
            if (encodedByteBudget <= 0 || encodedByteBudget > MAX_RETAINED_WINDOW_BYTES) {
                throw new VclException("WINDOW_INPUT_BYTE_BUDGET_INVALID", "");
            }
            var pages = new ArrayList<VclObservationPage>();
            var eventIds = new HashSet<UUID>();
            String cutDigest = input.cutDigest();
            var fingerprints = new StringBuilder("RULE_JOURNAL_INPUT/1\n").append(cutDigest);
            if (range != null) fingerprints.append('\n').append(range.startInclusive()).append('\n').append(range.endExclusive());
            String after = input.generationStartCheckpoint();
            long remainingBytes = encodedByteBudget;
            VclObservationPage page;
            do {
                owner.reauthorizeInputs();
                page = range == null ? input.readPage(after, 128)
                        : input.readPage(after, 128, range.startInclusive(), range.endExclusive());
                int bytes = page.bytes().length;
                if (bytes > remainingBytes) throw new VclException("WINDOW_INPUT_BYTE_BUDGET_EXCEEDED", "");
                remainingBytes -= bytes;
                for (var observation : page.observations()) {
                    if (!eventIds.add(UUID.fromString(observation.path("inputEventId").textValue()))) {
                        throw new VclException("WINDOW_INPUT_EVENT_DUPLICATE", "");
                    }
                    if (range != null && eventIds.size() > VclWindowAggregateKernel.MAX_INPUTS) {
                        throw new VclException("WINDOW_BATCH_INPUT_LIMIT", "");
                    }
                    fingerprints.append('\n').append(VclCanonical.sha256(VclCanonical.encode(observation)));
                }
                pages.add(page);
                after = page.nextCheckpoint();
            } while (page.hasMore());
            if (range == null && eventIds.size() != input.retainedObservationCount()) {
                throw new VclException("WINDOW_INPUT_CHECKPOINT_MISMATCH", "");
            }
            owner.reauthorizeInputs();
            return new RetainedWindowInput(cutDigest, VclCanonical.sha256(fingerprints.toString()),
                    pages, eventIds.size(), encodedByteBudget - remainingBytes);
        }

        /** Evaluate one exact subject/window over the retained prefix for diagnostics. */
        public WindowPreview preview(String subject, VclWindowPlan.Window window, long encodedByteBudget) {
            return preview(subject, window, encodedByteBudget, false);
        }

        WindowPreview preview(ScalarValue subject, VclWindowPlan.Window window, long byteBudget) {
            validateWindow("TEXT".equals(subject.literal().path("type").textValue())
                    ? subject.literal().path("value").textValue() : "", window);
            var evaluation = reduceWindow(readRetainedInput(journal, byteBudget, window), subject, window, false);
            owner.reauthorizeInputs();
            return evaluation;
        }

        private WindowPreview preview(String subject, VclWindowPlan.Window window, long encodedByteBudget, boolean ranged) {
            validateWindow(subject, window);
            var retained = readRetainedInput(journal, encodedByteBudget, ranged ? window : null);
            var evaluated = reduceWindow(retained, textSubject(subject), window, false);
            reauthorize();
            return prepared == null ? new WindowPreview(retained.inputDigest(), evaluated.localValue()) : evaluated;
        }

        private static ScalarValue textSubject(String subject) {
            return ScalarValue.known(PARSER, JsonNodeFactory.instance.objectNode().put("type", "TEXT").put("value", subject).toString());
        }

        private WindowPreview reduceWindow(RetainedWindowInput retained, ScalarValue subject, VclWindowPlan.Window window,
                boolean lowerBound) {
            var fingerprints = new StringBuilder("RULE_TYPED_AGGREGATE/1\n").append(retained.inputDigest())
                    .append('\n').append(compilation.accessScopeDigest()).append('\n').append(VclCanonical.encode(subject.literal()));
            var rows = new ArrayList<VclWindowAggregateKernel.Input>();
            UUID subjectId = UUID.fromString(subjectField);
            UUID valueId = valueField == null ? null : UUID.fromString(valueField);
            ScalarValue unresolved = null;
            for (var page : retained.pages()) {
                for (var event : page.observations()) {
                    var at = Instant.parse(event.path("eventTime").textValue());
                    if (!window.contains(at)) continue;
                    UUID id = UUID.fromString(event.path("inputEventId").textValue());
                    var sample = sustainedSample(event, id, at);
                    if (prepared == null && otherSubject(sample.fields().get(subjectId), subject)) continue;
                    for (var row : expandIntervalInput(prepared, input.steps(), sample, 1, fingerprints, filters)) {
                        var identity = row.fields().get(subjectId);
                        boolean knownSubject = identity != null && identity.status() == ScalarValue.Status.OK && !identity.isNull();
                        if (knownSubject && !subject.literal().equals(identity.literal())) continue;
                        String reason = null;
                        if (row.eligibility() != null) {
                            reason = "WINDOW_FILTER_UNKNOWN";
                        } else if (!knownSubject) {
                            reason = "WINDOW_SUBJECT_UNKNOWN";
                        } else if (timing.classify(at, sample.recordedAt()) == VclTimingPlan.Admission.QUARANTINED_FUTURE) {
                            reason = "WINDOW_FUTURE_OBSERVATION";
                        }
                        if (reason != null) {
                            if (unresolved == null) unresolved = ScalarValue.unknown(reason, List.of(id));
                            continue;
                        }
                        rows.add(new VclWindowAggregateKernel.Input(id, at, valueId == null ? null : row.fields().get(valueId)));
                    }
                }
            }
            var context = new VclWindowAggregateKernel.Context(VclWindowAggregateKernel.VERSION, plan.digest(),
                    compilation.accessScopeDigest(), window);
            var result = new VclWindowAggregateKernel().evaluate(plan, context, rows, compilation.accessScopeDigest());
            if (!lowerBound && !result.isFailure() && unresolved != null) result = unresolved;
            if (prepared != null) prepared.verifyManifests();
            return new WindowPreview(VclCanonical.sha256(fingerprints.toString()), result);
        }

        private static boolean otherSubject(ScalarValue identity, ScalarValue subject) {
            return identity != null && identity.status() == ScalarValue.Status.OK && !identity.isNull()
                    && !subject.literal().equals(identity.literal());
        }

        private void validateWindow(String subject, VclWindowPlan.Window window) {
            if (subject == null || subject.codePointCount(0, subject.length()) > 512
                    || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(subject)) {
                throw new VclException("WINDOW_SUBJECT_INVALID", "");
            }
            if (window == null || !plan.windows().assign(window.startInclusive()).contains(window)) {
                throw new VclException("AGGREGATE_WINDOW_CHANGED", "");
            }
        }

    }

    /** Diagnostic arithmetic with explicitly unknown upstream coverage and finalization. */
    public record WindowPreview(String inputDigest, ScalarValue localValue) {
        public String upstreamCoverage() { return "UNKNOWN"; }
        public String eventTimeProvenance() { return "UNQUALIFIED"; }
        public boolean finalized() { return false; }
    }

    private static JsonNode observationField(JsonNode observation, String id) {
        for (var field : observation.path("fields")) {
            if (id.equals(field.path("fieldId").textValue())) return field;
        }
        throw new VclException("WINDOW_INPUT_FIELD_MISSING", "");
    }

    private static ScalarValue observationValue(JsonNode field, UUID eventId) {
        if ("UNKNOWN".equals(field.path("knowledge").textValue())) {
            return ScalarValue.unknown("AGGREGATE_INPUT_UNKNOWN", List.of(eventId));
        }
        var literal = JsonNodeFactory.instance.objectNode().put("type", field.path("type").textValue());
        if ("DECIMAL".equals(field.path("type").textValue())) literal.put("unit", field.path("unit").textValue());
        literal.set("value", field.path("value"));
        return ScalarValue.known(PARSER, literal.toString());
    }

    /** Locally complete journal pages. Upstream coverage remains the page owner's UNKNOWN. */
    public record RetainedWindowInput(String cutDigest, String inputDigest, List<VclObservationPage> pages,
            int observationCount, long encodedBytes) {
        public RetainedWindowInput { pages = List.copyOf(pages); }
    }

    /** Bound assignment and reduction input for one pure window operator over a direct journal source. */
    public JournalWindowBatch prepareJournalWindowBatch(VclCompiler compiler, String nodeId, long membershipBudget) {
        return prepareJournalWindowBatch(compiler, nodeId, membershipBudget, false);
    }

    JournalWindowBatch prepareJournalWindowBatch(VclCompiler compiler, String nodeId, long membershipBudget, boolean ranged) {
        return prepareJournalWindowBatch(compile(compiler), new Metadata(), null, nodeId, membershipBudget, ranged);
    }

    private JournalWindowBatch prepareJournalWindowBatch(VclCompilation compilation, Metadata owner, PreparedLookups prepared,
            String nodeId, long membershipBudget, boolean ranged) {
        if (membershipBudget <= 0) throw new VclException("WINDOW_MEMBERSHIP_BUDGET_INVALID", "");
        if (journals.isEmpty()) throw new VclException("WINDOW_JOURNAL_REQUIRED", "");
        var node = compilation.graph().stream()
                .filter(candidate -> candidate.id().equals(nodeId) && candidate.method().equals("window_aggregate"))
                .findFirst().orElseThrow(() -> new VclException("WINDOW_OPERATOR_REQUIRED", ""));
        var input = intervalInput(compilation, node, prepared);
        var source = input.source();
        var config = source.config();
        var target = new SourceBindingSnapshots.Target(UUID.fromString(config.path("bindingId").asText()),
                config.path("schemaRevision").longValue());
        var journal = journals.get(target);
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        long rows = journal.retainedObservationCount();
        if (!ranged && rows > VclWindowAggregateKernel.MAX_INPUTS) throw new VclException("WINDOW_BATCH_INPUT_LIMIT", "");

        var decimal = document.spec().path("decimalPolicy");
        var policy = new ScalarDecimalPolicy(decimal.path("precision").intValue(), decimal.path("scale").intValue(),
                ScalarDecimalPolicy.Rounding.valueOf(decimal.path("rounding").asText()));
        var plan = VclWindowAggregateKernel.Plan.compile(compilation, nodeId, policy);
        long memberships = ranged ? membershipBudget : rows * plan.windows().maximumFanout();
        if (memberships > membershipBudget) throw new VclException("WINDOW_MEMBERSHIP_BUDGET_EXCEEDED", "");
        owner.reauthorizeInputs();
        var aggregate = node.config();
        return new JournalWindowBatch(plan, memberships, journal, VclTimingPlan.compile(compilation), aggregate.path("subjectFieldId").textValue(),
                aggregate.path("valueFieldId").textValue(), compilation, node, policy, input, prepared, owner);
    }

    record SessionObservation<T>(KeyedStateStore.Key key, T value, boolean included) { }
    record UnresolvedSessionInput(String subject, String occurrence, Instant at, long maximumAgeUs, VclTimingPlan timing) { }

    private static Optional<UnresolvedSessionInput> unresolvedSessionInput(JsonNode event, UUID subjectField,
            UUID occurrenceField, long maximumAgeUs, VclTimingPlan timing, VclJournalInputPlan input) {
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var at = Instant.parse(event.path("eventTime").textValue());
        var recorded = Instant.parse(event.path("recordedAt").textValue());
        if (timing.classify(at, recorded) == VclTimingPlan.Admission.QUARANTINED_FUTURE) return Optional.empty();
        var subject = identity(event, subjectField, id);
        var occurrence = identity(event, occurrenceField, id);
        if (subject != null && occurrence != null
                || input.select(observationFields(event, id)) == VclJournalInputPlan.Selection.EXCLUDED) return Optional.empty();
        return Optional.of(new UnresolvedSessionInput(subject, occurrence, at, maximumAgeUs, timing));
    }

    final class SessionBatch {
        private final com.voltcanary.analytics.vcl.VclWindowSessionKernel.Plan plan;
        private final VclTimingPlan timing;
        private final SourceCaptureSnapshot journal;
        private final VclJournalInputPlan input;

        SessionBatch(com.voltcanary.analytics.vcl.VclWindowSessionKernel.Plan plan, VclTimingPlan timing,
                SourceCaptureSnapshot journal, VclJournalInputPlan input) {
            this.plan = plan;
            this.timing = timing;
            this.journal = journal;
            this.input = input;
        }

        Optional<SessionObservation<VclWindowSessionKernel.Observation>> route(UUID epoch, JsonNode event) {
            var at = Instant.parse(event.path("eventTime").textValue());
            var recorded = Instant.parse(event.path("recordedAt").textValue());
            if (timing.classify(at, recorded) == VclTimingPlan.Admission.QUARANTINED_FUTURE) return Optional.empty();
            UUID id = UUID.fromString(event.path("inputEventId").textValue());
            var fields = observationFields(event, id);
            var subject = fields.get(plan.subjectField());
            var occurrence = fields.get(plan.occurrenceField());
            if (subject == null || occurrence == null || subject.status() != ScalarValue.Status.OK
                    || occurrence.status() != ScalarValue.Status.OK || subject.isNull() || occurrence.isNull()) return Optional.empty();
            var selection = input.select(fields);
            if (selection == VclJournalInputPlan.Selection.EXCLUDED) return Optional.empty();
            var key = new com.voltcanary.analytics.vcl.KeyedStateStore.Key(journal.accountId(), plan.rule(), epoch,
                    com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(subject),
                    com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(occurrence));
            return Optional.of(new SessionObservation<>(key, new VclWindowSessionKernel.Observation(id, at, fields),
                    selection == VclJournalInputPlan.Selection.INCLUDED));
        }

        Optional<UnresolvedSessionInput> unresolved(JsonNode event) {
            return unresolvedSessionInput(event, plan.subjectField(), plan.occurrenceField(), plan.maxDurationUs(), timing, input);
        }

        List<VclWindowSessionKernel.Observation> replayHistory(JdbcRuleDeployments.Deployment deployment,
                KeyedStateStore.Key key, String checkpoint, Instant from, Instant through) {
            return sessionReplayRows(journal.atCheckpoint(checkpoint), deployment, from, through, event -> route(deployment.epoch(), event)
                    .filter(value -> value.included() && key.equals(value.key()))
                    .map(SessionObservation::value));
        }

        com.voltcanary.analytics.vcl.DurableWindowSession durable(JdbcKeyedStateStore store,
                JdbcRuleDeployments.Deployment deployment, com.voltcanary.analytics.vcl.KeyedStateStore.Key key,
                Runnable deploymentCheck) {
            return durable(store, deployment, key, deploymentCheck, null, null, null);
        }

        com.voltcanary.analytics.vcl.DurableWindowSession durable(JdbcKeyedStateStore store,
                JdbcRuleDeployments.Deployment deployment, KeyedStateStore.Key key, Runnable deploymentCheck,
                String absence, String checkpoint, Instant observedAt) {
            return new com.voltcanary.analytics.vcl.DurableWindowSession(store, plan, key, deployment.bundleDigest(),
                    () -> {
                        reauthorize();
                        deploymentCheck.run();
                    }, (target, lease, transition, openedAt) -> store.commitSession(target, lease,
                            SessionAbsenceSchedule.schedule(store, target, lease, transition, absence, checkpoint, openedAt,
                                    observedAt, false, timing),
                            openedAt, deployment),
                    timing::windowCloseDueAt);
        }
    }

    SessionBatch prepareWindowSession(VclCompiler compiler, String nodeId) {
        var compilation = compile(compiler);
        var node = compilation.graph().stream().filter(candidate -> nodeId.equals(candidate.id())
                && "window_session".equals(candidate.method())).findFirst()
                .orElseThrow(() -> new VclException("SESSION_OPERATOR_REQUIRED", ""));
        var input = VclJournalInputPlan.compile(compilation, node, new Metadata());
        var source = input.source();
        var target = new SourceBindingSnapshots.Target(UUID.fromString(source.config().path("bindingId").textValue()),
                source.config().path("schemaRevision").longValue());
        var journal = journals.get(target);
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        var plan = com.voltcanary.analytics.vcl.VclWindowSessionKernel.compile(compilation, nodeId, new Metadata());
        return new SessionBatch(plan, VclTimingPlan.compile(compilation), journal, input);
    }

    final class ZoneBatch {
        private final VclCompilation compilation;
        private final VclCompilation.Node node;
        private final VclTimingPlan timing;
        private final SourceCaptureSnapshot journal;
        private final VclJournalInputPlan input;

        ZoneBatch(VclCompilation compilation, VclCompilation.Node node, SourceCaptureSnapshot journal, VclJournalInputPlan input) {
            this.compilation = compilation;
            this.node = node;
            this.journal = journal;
            this.input = input;
            timing = VclTimingPlan.compile(compilation);
        }

        Optional<SessionObservation<VclZoneSessionKernel.Observation>> route(UUID epoch, JsonNode event) {
            var at = Instant.parse(event.path("eventTime").textValue());
            var recorded = Instant.parse(event.path("recordedAt").textValue());
            if (timing.classify(at, recorded) == VclTimingPlan.Admission.QUARANTINED_FUTURE) return Optional.empty();
            UUID id = UUID.fromString(event.path("inputEventId").textValue());
            var config = node.config();
            var subject = observationValue(observationField(event, config.path("subjectFieldId").textValue()), id);
            var occurrence = observationValue(observationField(event, config.path("occurrenceFieldId").textValue()), id);
            if (subject.status() != ScalarValue.Status.OK || occurrence.status() != ScalarValue.Status.OK
                    || subject.isNull() || occurrence.isNull()) return Optional.empty();
            var selection = input.select(observationFields(event, id));
            if (selection == VclJournalInputPlan.Selection.EXCLUDED) return Optional.empty();
            var position = observationValue(observationField(event, config.path("positionFieldId").textValue()), id);
            var zone = observationValue(observationField(event, config.path("zoneFieldId").textValue()), id);
            var accuracyId = config.path("accuracyFieldId").textValue();
            var accuracy = accuracyId == null ? null : observationValue(observationField(event, accuracyId), id);
            var key = new com.voltcanary.analytics.vcl.KeyedStateStore.Key(journal.accountId(),
                    UUID.fromString(document.spec().path("ruleId").textValue()), epoch,
                    com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(subject),
                    com.voltcanary.analytics.vcl.VclWindowSessionKernel.identity(occurrence));
            return Optional.of(new SessionObservation<>(key, VclZoneSessionKernel.observation(id, at, key, zone, position, accuracy),
                    selection == VclJournalInputPlan.Selection.INCLUDED));
        }

        Optional<UnresolvedSessionInput> unresolved(JsonNode event) {
            var config = node.config();
            return unresolvedSessionInput(event, UUID.fromString(config.path("subjectFieldId").textValue()),
                    UUID.fromString(config.path("occurrenceFieldId").textValue()),
                    Math.multiplyExact(2, Long.parseLong(config.path("maxDurationUs").textValue())), timing, input);
        }

        List<VclZoneSessionKernel.Observation> replayHistory(JdbcRuleDeployments.Deployment deployment,
                KeyedStateStore.Key key, String checkpoint, Instant from, Instant through) {
            return sessionReplayRows(journal.atCheckpoint(checkpoint), deployment, from, through, event -> route(deployment.epoch(), event)
                    .filter(value -> value.included() && key.equals(value.key())).map(SessionObservation::value));
        }

        com.voltcanary.analytics.vcl.DurableZoneSession durable(JdbcKeyedStateStore store,
                JdbcRuleDeployments.Deployment deployment, com.voltcanary.analytics.vcl.KeyedStateStore.Key key,
                UUID zone, long revision, WorkerCircleReads circles, Runnable deploymentCheck) {
            return durable(store, deployment, key, zone, revision, circles, deploymentCheck, null, null, null);
        }

        com.voltcanary.analytics.vcl.DurableZoneSession durable(JdbcKeyedStateStore store,
                JdbcRuleDeployments.Deployment deployment, KeyedStateStore.Key key, UUID zone, long revision,
                WorkerCircleReads circles, Runnable deploymentCheck, String absence, String checkpoint, Instant observedAt) {
            String scope = compilation.accessScopeDigest();
            var geometry = circles.read(deployment, zone, revision, scope);
            var plan = com.voltcanary.analytics.vcl.VclZoneSessionKernel.compile(compilation, node.id(), geometry, zone);
            return new com.voltcanary.analytics.vcl.DurableZoneSession(store, plan, key, deployment.bundleDigest(),
                    () -> {
                        reauthorize();
                        deploymentCheck.run();
                    }, (target, lease, transition, openedAt) -> store.commitSession(target, lease,
                            SessionAbsenceSchedule.schedule(store, target, lease, transition, absence, checkpoint, openedAt,
                                    observedAt, true, timing),
                            openedAt, deployment),
                    timing::windowCloseDueAt, () -> circles.current(deployment, zone, plan.circle().revision(), scope));
        }
    }

    private <T> List<T> sessionReplayRows(SourceCaptureSnapshot journal, JdbcRuleDeployments.Deployment deployment,
            Instant from, Instant through, java.util.function.Function<JsonNode, Optional<T>> route) {
        var boundary = deployment.start().boundary();
        if (deployment.resumeFrom() != null && deployment.resumeFrom().isAfter(boundary)) boundary = deployment.resumeFrom();
        if (from.isBefore(boundary)) from = boundary;
        var until = through.plus(1, java.time.temporal.ChronoUnit.MICROS);
        var result = new ArrayList<T>();
        long remaining = MAX_RETAINED_WINDOW_BYTES;
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
                    route.apply(event).ifPresent(value -> {
                        if (result.size() >= VclDurationWitnessKernel.MAX_INPUTS) {
                            throw new VclException("SESSION_REPLAY_INPUT_LIMIT", "");
                        }
                        result.add(value);
                    });
                }
                after = page.nextCheckpoint();
            } while (page.hasMore());
            from = end;
        }
        reauthorize();
        return List.copyOf(result);
    }

    ZoneBatch prepareZoneSession(VclCompiler compiler, String nodeId) {
        var compilation = compile(compiler);
        var node = compilation.graph().stream().filter(candidate -> nodeId.equals(candidate.id())
                && "zone_session".equals(candidate.method())).findFirst()
                .orElseThrow(() -> new VclException("SESSION_OPERATOR_REQUIRED", ""));
        var input = VclJournalInputPlan.compile(compilation, node, new Metadata());
        var source = input.source();
        var target = new SourceBindingSnapshots.Target(UUID.fromString(source.config().path("bindingId").textValue()),
                source.config().path("schemaRevision").longValue());
        var journal = journals.get(target);
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        return new ZoneBatch(compilation, node, journal, input);
    }

    VclSessionAbsenceKernel.Plan prepareSessionAbsence(VclCompiler compiler, String nodeId,
            VclDurationWitnessKernel.HoldContract hold, SpatialFeatureSnapshot geometry, UUID zone) {
        return VclSessionAbsenceKernel.compile(compile(compiler), nodeId, new Metadata(), hold, geometry, zone);
    }

    String sessionScope() { return scope; }

    List<UUID> retainedWindowReferences(JsonNode result, Instant windowStart, Instant now) {
        reauthorize();
        if (!now.isBefore(windowStart.plus(policy.retentionDays(), java.time.temporal.ChronoUnit.DAYS))) return List.of();
        var evidence = result.path("evidence");
        if (!evidence.isArray()) throw new VclException("WINDOW_MEASURE_CHANGED", "");
        var references = new java.util.LinkedHashSet<UUID>();
        try {
            for (var item : evidence) {
                references.add(UUID.fromString(item.textValue()));
                if (references.size() >= policy.observationReferenceLimit()) break;
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new VclException("WINDOW_MEASURE_CHANGED", "");
        }
        return List.copyOf(references);
    }

    public VclSustainedPlan prepareSustained(VclCompiler compiler, String nodeId) {
        return VclSustainedPlan.compile(compile(compiler), nodeId, new Metadata());
    }

    public VclIntervalUnionPlan prepareIntervalUnion(VclCompiler compiler, String nodeId) {
        return VclIntervalUnionPlan.compile(compile(compiler), nodeId, new Metadata());
    }

    public WindowPreview previewDirectIntervalUnion(VclCompiler compiler, String nodeId, String subjectDigest,
            VclWindowPlan.Window window, long byteBudget) {
        return previewJournalIntervalUnion(compile(compiler), new Metadata(), null, nodeId, subjectDigest, window,
                byteBudget, null, null, window.endExclusive());
    }

    String sourceCutDigest() {
        if (journals.size() != 1) throw new VclException("WINDOW_DIRECT_JOURNAL_REQUIRED", "");
        return journals.values().iterator().next().cutDigest();
    }

    record IntervalFinalization(WindowPreview result, VclSourceCoverage proof, String cutDigest, String checkpoint,
            Instant measuredThrough) { }

    void stageIntervalWindows(VclCompiler compiler, String nodeId, SourceBindingSnapshots.Target target,
            JdbcRuleDeployments.Deployment deployment, JdbcKeyedStateStore store, JsonNode event, UUID holder) {
        stageIntervalWindows(compile(compiler), new Metadata(), null, nodeId, target, deployment, store, event, holder);
    }

    private void stageIntervalWindows(VclCompilation compilation, Metadata owner, PreparedLookups prepared,
            String nodeId, SourceBindingSnapshots.Target target, JdbcRuleDeployments.Deployment deployment,
            JdbcKeyedStateStore store, JsonNode event, UUID holder) {
        var plan = VclIntervalUnionPlan.compile(compilation, nodeId, owner);
        var timing = VclTimingPlan.compile(compilation);
        var journal = journals.get(target);
        if (journal == null || !deployment.account().equals(journal.accountId())) {
            throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        }
        var definition = journal.binding(target.id(), target.revision()).orElseThrow().definition();
        var contract = com.voltcanary.vcl.VclSourceBindingProfile.intervalFields(definition)
                .orElseThrow(() -> new VclException("INTERVAL_REPORT_CONTRACT_REQUIRED", ""));
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var eventTime = Instant.parse(event.path("eventTime").textValue());
        if (timing.classify(eventTime, Instant.parse(event.path("recordedAt").textValue()))
                == VclTimingPlan.Admission.QUARANTINED_FUTURE) return;
        var operator = intervalOperator(compilation, nodeId);
        var input = intervalInput(compilation, operator, prepared);
        int firstPrefix = "LOWER_BOUND_PROVEN".equals(operator.config().path("emit").textValue()) ? 1 : 0;
        if (!target.id().toString().equals(input.source().config().path("bindingId").textValue())
                || target.revision() != input.source().config().path("schemaRevision").longValue()) {
            throw new VclException("INTERVAL_SOURCE_COVERAGE_UNAVAILABLE", "");
        }
        var sample = sustainedSample(event, id, eventTime);
        requireIntervalReport(new VclIntervalUnionPlan.Row(id, sample.fields()), contract, eventTime);
        var rows = expandIntervalInput(prepared, input.steps(), sample, VclIntervalUnionKernel.MAX_INPUTS, new StringBuilder(),
                intervalFilters(compilation, input, owner));
        var generation = journal.inputGeneration();
        var route = prepared == null ? null : IntervalWindowSchedule.Route.from(target.id(), event, owner::reauthorizeInputs);
        var assignments = new LinkedHashMap<KeyedStateStore.Key, IntervalWindowSchedule.Recipe>();
        var unresolvedWindows = new java.util.LinkedHashSet<String>();
        int memberships = 0;
        for (var row : rows) {
            var subjectValue = row.fields().get(plan.subjectField());
            var start = intervalBound(row, contract.start());
            var end = intervalBound(row, contract.end());
            if (subjectValue != null && subjectValue.isFailure()) throw new VclException(subjectValue.reason(), "");
            boolean unresolved = subjectValue == null || subjectValue.status() != ScalarValue.Status.OK || subjectValue.isNull();
            if (start == null || end == null) throw new VclException("INTERVAL_REPORT_BOUNDS_CHANGED", "");
            for (var window : plan.overlappingWindows(start, end)) {
                if (!deployment.includesWindow(window.startInclusive())) continue;
                if (++memberships > VclIntervalUnionKernel.MAX_INPUTS) throw new VclException("WINDOW_FANOUT_LIMIT", "");
                if (unresolved) {
                    unresolvedWindows.add(IntervalWindowSchedule.occurrence(window));
                    continue;
                }
                String subject = VclWindowSessionKernel.identity(subjectValue);
                var dueAt = timing.windowCloseDueAt(plan.reportingHorizon(window, definition));
                var key = new KeyedStateStore.Key(deployment.account(), deployment.rule(), deployment.epoch(), subject,
                        IntervalWindowSchedule.occurrence(window));
                assignments.putIfAbsent(key,
                        new IntervalWindowSchedule.Recipe(nodeId, plan.digest(), generation, window, dueAt,
                                VclCanonical.encode(subjectValue.literal()), firstPrefix, null, 0, null));
            }
        }
        long checkpoint = VclObservationReadiness.checkpoint(event.path("checkpoint").textValue());
        for (var assignment : assignments.entrySet()) {
            owner.reauthorizeInputs();
            IntervalWindowSchedule.stage(store, deployment, assignment.getKey(), assignment.getValue(), checkpoint, holder, route);
        }
        for (var candidate : store.registeredWindows(deployment, List.copyOf(unresolvedWindows))) {
            owner.reauthorizeInputs();
            IntervalWindowSchedule.stageCorrected(store, deployment, candidate, checkpoint, holder);
        }
    }

    private static Instant intervalBound(VclIntervalUnionPlan.Row row, UUID field) {
        var value = row.fields().get(field);
        return value == null || value.status() != ScalarValue.Status.OK || value.isNull() ? null
                : Instant.parse(value.literal().path("value").textValue());
    }

    static VclWindowPlan.Window correctionRange(JsonNode event, SourceCaptureSnapshot journal,
            SourceBindingSnapshots.Target target, boolean interval) {
        var at = Instant.parse(event.path("eventTime").textValue());
        if (!interval) return new VclWindowPlan.Window(at, at.plus(1, java.time.temporal.ChronoUnit.MICROS));
        var definition = journal.binding(target.id(), target.revision())
                .orElseThrow(() -> new VclException("SOURCE_CAPTURE_UNAVAILABLE", "")).definition();
        var contract = com.voltcanary.vcl.VclSourceBindingProfile.intervalFields(definition)
                .orElseThrow(() -> new VclException("INTERVAL_REPORT_CONTRACT_REQUIRED", ""));
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var row = new VclIntervalUnionPlan.Row(id, observationFields(event, id));
        return requireIntervalReport(row, contract, at);
    }

    private static VclWindowPlan.Window requireIntervalReport(VclIntervalUnionPlan.Row row,
            com.voltcanary.vcl.VclSourceBindingProfile.IntervalFields contract, Instant eventTime) {
        var start = intervalBound(row, contract.start());
        var end = intervalBound(row, contract.end());
        if (start == null || end == null || !start.isBefore(end) || !end.equals(eventTime)
                || java.time.Duration.between(start, end).compareTo(
                        java.time.Duration.of(contract.maxSpanUs(), java.time.temporal.ChronoUnit.MICROS)) > 0) {
            throw new VclException("INTERVAL_REPORT_BOUNDS_CHANGED", "");
        }
        return new VclWindowPlan.Window(start, end);
    }

    JournalWindowDecision intervalDecision(VclCompiler compiler, String nodeId, ScalarValue subject) {
        var compilation = compile(compiler);
        return JournalWindowDecision.compileTyped(compilation, intervalOperator(compilation, nodeId),
                intervalDecimalPolicy(), new Metadata(), subject);
    }

    String intervalEvaluatorPlanDigest(VclCompiler compiler, String nodeId, String bundleDigest) {
        return intervalEvaluatorPlanDigest(compile(compiler), new Metadata(), null, nodeId, bundleDigest);
    }

    private String intervalEvaluatorPlanDigest(VclCompilation compilation, Metadata owner, PreparedLookups prepared,
            String nodeId, String bundleDigest) {
        var operator = intervalOperator(compilation, nodeId);
        var input = intervalInput(compilation, operator, prepared);
        var methods = compilation.graph().stream().map(VclCompilation.Node::method)
                .filter(method -> !java.util.Set.of("lookup_asof", "filter").contains(method)).toList();
        if (methods.size() != 4 || !java.util.Set.copyOf(methods).equals(java.util.Set.of(
                "read_stream", "interval_union_window", "compare", "emit_alert"))
                || compilation.graph().size() != methods.size() + input.steps().size()
                || !java.util.Set.of("ON_CLOSE", "LOWER_BOUND_PROVEN").contains(operator.config().path("emit").asText())) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        var decision = JournalWindowDecision.compileShape(compilation, operator, intervalDecimalPolicy(), owner);
        intervalFilters(compilation, input, owner);
        sessionAlertIntent(decision.nodeId(), bundleDigest).orElseThrow(() -> new VclException("EVALUATOR_PROFILE_UNAVAILABLE", ""));
        var plan = VclIntervalUnionPlan.compile(compilation, nodeId, owner);
        var timing = VclTimingPlan.compile(compilation);
        var sources = timing.requiredInputs().stream().filter(dependency -> dependency.kind() == VclMetadata.Resource.BINDING).toList();
        if (sources.size() != 1) throw new VclException("INTERVAL_SOURCE_COVERAGE_UNAVAILABLE", "");
        var source = sources.getFirst();
        var journal = journals.get(new SourceBindingSnapshots.Target(source.id(), source.revision()));
        if (source.kind() != VclMetadata.Resource.BINDING || journal == null) {
            throw new VclException("INTERVAL_SOURCE_COVERAGE_UNAVAILABLE", "");
        }
        var schema = journal.binding(source.id(), source.revision()).orElseThrow().definition();
        var latest = timing.windowCloseDueAt(Instant.EPOCH.plus(plan.maximumReportingRangeUs(schema),
                java.time.temporal.ChronoUnit.MICROS));
        if (latest.isAfter(Instant.EPOCH.plus(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL))) {
            throw new VclException("INTERVAL_COVERAGE_RANGE_LIMIT", "");
        }
        owner.reauthorizeInputs();
        return VclCanonical.sha256(String.join("\n", "DIRECT_JOURNAL_INTERVAL_EVALUATOR/1",
                plan.digest(), timing.digest(), decision.digest(), journal.cutDigest()));
    }

    private static VclCompilation.Node intervalOperator(VclCompilation compilation, String nodeId) {
        return compilation.graph().stream().filter(node -> node.id().equals(nodeId)
                && "interval_union_window".equals(node.method())).findFirst()
                .orElseThrow(() -> new VclException("INTERVAL_OPERATOR_REQUIRED", ""));
    }

    private ScalarDecimalPolicy intervalDecimalPolicy() {
        var decimal = document.spec().path("decimalPolicy");
        return new ScalarDecimalPolicy(decimal.path("precision").intValue(), decimal.path("scale").intValue(),
                ScalarDecimalPolicy.Rounding.valueOf(decimal.path("rounding").textValue()));
    }

    Optional<IntervalFinalization> finalizeDirectIntervalUnion(VclCompiler compiler, String nodeId, String subjectDigest,
            VclWindowPlan.Window window, long minimumCheckpoint, java.util.function.BiFunction<Instant, Instant, UUID> discoverProof) {
        return finalizeJournalIntervalUnion(compile(compiler), new Metadata(), null, nodeId, subjectDigest, window,
                window.endExclusive(), minimumCheckpoint, discoverProof);
    }

    Optional<IntervalFinalization> proveDirectIntervalPrefix(VclCompiler compiler, String nodeId, String subjectDigest,
            VclWindowPlan.Window window, Instant through, long minimumCheckpoint,
            java.util.function.BiFunction<Instant, Instant, UUID> discoverProof) {
        var compilation = compile(compiler);
        requireIntervalLowerBound(compilation, nodeId, window, through);
        return finalizeJournalIntervalUnion(compilation, new Metadata(), null, nodeId, subjectDigest, window, through,
                minimumCheckpoint, discoverProof);
    }

    private static void requireIntervalLowerBound(VclCompilation compilation, String nodeId,
            VclWindowPlan.Window window, Instant through) {
        if (!"LOWER_BOUND_PROVEN".equals(intervalOperator(compilation, nodeId).config().path("emit").textValue())
                || !through.isAfter(window.startInclusive()) || !through.isBefore(window.endExclusive())) {
            throw new VclException("INTERVAL_LOWER_BOUND_UNAVAILABLE", "");
        }
    }

    private Optional<IntervalFinalization> finalizeJournalIntervalUnion(VclCompilation compilation, Metadata owner,
            PreparedLookups prepared, String nodeId, String subjectDigest, VclWindowPlan.Window window,
            Instant measuredThrough, long minimumCheckpoint, java.util.function.BiFunction<Instant, Instant, UUID> discoverProof) {
        var plan = VclIntervalUnionPlan.compile(compilation, nodeId, owner);
        var timing = VclTimingPlan.compile(compilation);
        var sources = timing.requiredInputs().stream().filter(input -> input.kind() == VclMetadata.Resource.BINDING).toList();
        if (sources.size() != 1 || prepared == null && timing.requiredInputs().size() != 1) {
            throw new VclException("INTERVAL_SOURCE_COVERAGE_UNAVAILABLE", "");
        }
        var source = sources.getFirst();
        var journal = journals.get(new SourceBindingSnapshots.Target(source.id(), source.revision()));
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        if (minimumCheckpoint > VclObservationReadiness.checkpoint(journal.capturedHeadCheckpoint())) {
            journal = journal.atCheckpoint(Long.toString(minimumCheckpoint));
        }
        var definition = journal.binding(source.id(), source.revision()).orElseThrow().definition();
        var horizon = plan.reportingHorizon(window, measuredThrough, definition);
        var from = window.startInclusive();
        var due = timing.windowCloseDueAt(horizon);
        if (java.time.Duration.between(from, due).compareTo(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL) > 0) {
            throw new VclException("INTERVAL_COVERAGE_RANGE_LIMIT", "");
        }
        UUID proofId = discoverProof.apply(from, due);
        if (proofId == null) return Optional.empty();
        var proof = journal.coverage(proofId, from, due, true);
        if (!proof.qualifiesEventTime()) return Optional.empty();
        // The sealed reference manifests are a separate gate, not source event-time progress.
        if (proof.evidence().until().isBefore(due) || timing.classify(proof.evidence().until(), proof.acceptedAt())
                == VclTimingPlan.Admission.QUARANTINED_FUTURE) return Optional.empty();
        var covered = journal.forFinalization(proof);
        var result = previewJournalIntervalUnion(compilation, owner, prepared, nodeId, subjectDigest, window,
                MAX_RETAINED_WINDOW_BYTES, covered, horizon, measuredThrough);
        if (!proof.equals(covered.coverage(proofId, from, due))) throw new VclException("INTERVAL_COVERAGE_CHANGED", "");
        if (prepared != null) prepared.verifyManifests();
        owner.reauthorizeInputs();
        return Optional.of(new IntervalFinalization(result, proof, covered.cutDigest(), covered.capturedHeadCheckpoint(), measuredThrough));
    }

    private WindowPreview previewJournalIntervalUnion(VclCompilation compilation, Metadata owner, PreparedLookups prepared,
            String nodeId, String subjectDigest, VclWindowPlan.Window window, long byteBudget,
            SourceCaptureSnapshot covered, Instant reportingHorizon, Instant measuredThrough) {
        if (byteBudget <= 0 || byteBudget > MAX_RETAINED_WINDOW_BYTES) {
            throw new VclException("WINDOW_INPUT_BYTE_BUDGET_INVALID", "");
        }
        var input = intervalInput(compilation, intervalOperator(compilation, nodeId), prepared);
        var filters = intervalFilters(compilation, input, owner);
        var source = input.source();
        var journal = journals.get(new SourceBindingSnapshots.Target(UUID.fromString(source.config().path("bindingId").textValue()),
                source.config().path("schemaRevision").longValue()));
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        if (covered != null) journal = covered;
        var plan = VclIntervalUnionPlan.compile(compilation, nodeId, owner);
        var horizon = reportingHorizon;
        if (horizon == null) {
            var definition = journal.binding(UUID.fromString(source.config().path("bindingId").textValue()),
                    source.config().path("schemaRevision").longValue()).orElseThrow().definition();
            horizon = plan.reportingHorizon(window, measuredThrough, definition);
        }
        var timing = VclTimingPlan.compile(compilation);
        var fingerprints = new StringBuilder(String.join("\n", "RULE_INTERVAL_UNION_JOURNAL/1", journal.cutDigest(),
                plan.digest(), subjectDigest, window.startInclusive().toString(), window.endExclusive().toString()));
        if (!measuredThrough.equals(window.endExclusive())) fingerprints.append("\nprefix\n").append(measuredThrough);
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
                if (rows.size() >= VclIntervalUnionKernel.MAX_INPUTS) throw new VclException("INTERVAL_INPUT_LIMIT", "");
                var sample = sustainedSample(event, id, Instant.parse(event.path("eventTime").textValue()));
                if (future == null && timing.classify(sample.at(), sample.recordedAt()) == VclTimingPlan.Admission.QUARANTINED_FUTURE) {
                    future = id;
                }
                fingerprints.append('\n').append(VclCanonical.sha256(VclCanonical.encode(event)));
                rows.addAll(expandIntervalInput(prepared, input.steps(), sample,
                        VclIntervalUnionKernel.MAX_INPUTS - rows.size(), fingerprints, filters));
            }
            after = page.nextCheckpoint();
        } while (page.hasMore());
        var result = plan.evaluatePrefix(subjectDigest, window, measuredThrough, rows, owner.accessScopeDigest());
        if (future != null && !result.isFailure()) result = ScalarValue.unknown("INTERVAL_FUTURE_OBSERVATION", List.of(future));
        owner.reauthorizeInputs();
        return new WindowPreview(VclCanonical.sha256(fingerprints.toString()), result);
    }

    private record IntervalInput(VclCompilation.Node source, List<VclCompilation.Node> steps) { }

    private static IntervalInput intervalInput(VclCompilation compilation, VclCompilation.Node operator, PreparedLookups prepared) {
        var nodes = new java.util.HashMap<String, VclCompilation.Node>();
        compilation.graph().forEach(node -> nodes.put(node.id(), node));
        var lookups = new ArrayList<VclCompilation.Node>();
        var source = nodes.get(operator.inputs().get("events"));
        while (source != null && ("filter".equals(source.method()) || prepared != null && "lookup_asof".equals(source.method()))) {
            var temporal = source.config().path("temporal");
            if ("INTERVAL_SPLIT".equals(temporal.path("mode").textValue())
                    && (!temporal.path("startFieldId").equals(operator.config().path("startFieldId"))
                    || !temporal.path("endFieldId").equals(operator.config().path("endFieldId")))) {
                throw new VclException("INTERVAL_LOOKUP_BOUNDS_CHANGED", "");
            }
            lookups.addFirst(source);
            source = nodes.get(source.inputs().get("events"));
        }
        if (source == null || !"read_stream".equals(source.method())) {
            throw new VclException("window_aggregate".equals(operator.method())
                    ? "WINDOW_DIRECT_JOURNAL_REQUIRED" : "INTERVAL_DIRECT_JOURNAL_REQUIRED", "");
        }
        return new IntervalInput(source, List.copyOf(lookups));
    }

    private Map<String, java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>> intervalFilters(
            VclCompilation compilation, IntervalInput input, Metadata owner) {
        var filters = new java.util.HashMap<String, java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>>();
        var units = com.voltcanary.vcl.VclUnits.standard();
        var registry = new com.voltcanary.vcl.VclRegistry(PARSER);
        var compiler = new com.voltcanary.vcl.VclScalarCompiler(PARSER, registry, units);
        var evaluator = new com.voltcanary.analytics.vcl.VclScalarEvaluator(units, registry.digest());
        var decimal = intervalDecimalPolicy();
        for (var step : input.steps()) {
            if (!"filter".equals(step.method())) continue;
            var fields = new java.util.LinkedHashMap<UUID, com.voltcanary.vcl.VclType>();
            step.fields().forEach((id, type) -> fields.put(UUID.fromString(id), type));
            var predicate = compiler.compile(step.config().path("predicate").toString(), fields, owner);
            filters.put(step.id(), row -> evaluator.evaluate(predicate, row.fields(), decimal, compilation.accessScopeDigest()));
        }
        return Map.copyOf(filters);
    }

    private static List<VclIntervalUnionPlan.Row> expandIntervalInput(PreparedLookups prepared,
            List<VclCompilation.Node> steps, VclSustainedPlan.Sample sample, int limit, StringBuilder fingerprints,
            Map<String, java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>> filters) {
        List<VclIntervalUnionPlan.Row> expanded = List.of(new VclIntervalUnionPlan.Row(sample.id(), sample.fields()));
        for (var step : steps) {
            var projected = new ArrayList<VclIntervalUnionPlan.Row>();
            for (var row : expanded) {
                var filter = filters.get(step.id());
                if (filter == null) projectIntervalLookup(prepared, step, row, sample.at(), projected, fingerprints, limit);
                else {
                    var value = filter.apply(row);
                    if (value.isFailure()) throw new VclException(value.reason(), "");
                    if (value.status() == ScalarValue.Status.OK && !value.isNull()) {
                        if (value.literal().path("value").booleanValue()) projected.add(row);
                    } else {
                        projected.add(new VclIntervalUnionPlan.Row(row.observation(), row.fields(),
                                ScalarValue.unknown("INTERVAL_FILTER_UNKNOWN", List.of(row.observation()))));
                    }
                }
                if (projected.size() > limit) throw new VclException("INTERVAL_INPUT_LIMIT", "");
            }
            expanded = projected;
        }
        return expanded;
    }

    private static void projectIntervalLookup(PreparedLookups prepared, VclCompilation.Node lookup,
            VclIntervalUnionPlan.Row row, Instant eventTime, List<VclIntervalUnionPlan.Row> projected,
            StringBuilder fingerprints, int limit) {
        var wire = new java.util.LinkedHashMap<UUID, JsonNode>();
        row.fields().forEach((id, value) -> {
            if (value.status() == ScalarValue.Status.OK) wire.put(id, value.literal());
        });
        JsonNode result;
        try {
            result = prepared.lookup(lookup.id(), wire, eventTime);
        } catch (VclException unresolved) {
            if (!"ASOF_KEY_UNKNOWN".equals(unresolved.code()) && !"ASOF_TIME_INVALID".equals(unresolved.code())) throw unresolved;
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
        if (segments.size() > limit - projected.size()) throw new VclException("INTERVAL_INPUT_LIMIT", "");
        for (var segment : segments) {
            String identity = String.join("\n", row.observation().toString(), lookup.id(),
                    segment.path("start").textValue(), segment.path("end").textValue());
            UUID id = UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var fields = new java.util.LinkedHashMap<>(row.fields());
            fields.put(start, intervalInstant(segment.path("start").textValue()));
            fields.put(end, intervalInstant(segment.path("end").textValue()));
            projected.add(mergeIntervalLookup(lookup, new VclIntervalUnionPlan.Row(id, fields, row.eligibility()), segment, null));
        }
    }

    private static VclIntervalUnionPlan.Row mergeIntervalLookup(VclCompilation.Node lookup, VclIntervalUnionPlan.Row row,
            JsonNode outcome, String unresolved) {
        var fields = new java.util.LinkedHashMap<>(row.fields());
        for (var field : lookup.config().path("select")) {
            UUID id = UUID.fromString(field.textValue());
            var literal = outcome == null ? null : outcome.path("row").path("values").get(field.textValue());
            ScalarValue value;
            if (outcome == null || "UNKNOWN".equals(outcome.path("status").textValue())) {
                value = ScalarValue.unknown(unresolved == null ? outcome.path("reason").textValue() : unresolved,
                        List.of(row.observation()));
            } else {
                if (literal == null) throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                value = ScalarValue.known(PARSER, literal.toString());
                var expected = lookup.fields().get(field.textValue());
                if (!value.isNull() && (!expected.kind().name().equals(literal.path("type").textValue())
                        || expected.kind() == com.voltcanary.vcl.VclType.Kind.DECIMAL
                        && !expected.unit().equals(literal.path("unit").textValue()))) {
                    throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                }
            }
            var previous = fields.putIfAbsent(id, value);
            if (previous != null && (previous.status() != ScalarValue.Status.OK
                    || value.status() != ScalarValue.Status.OK || !previous.literal().equals(value.literal()))) {
                fields.put(id, ScalarValue.unknown("ASOF_FIELD_CONFLICT", List.of(row.observation())));
            }
        }
        return new VclIntervalUnionPlan.Row(row.observation(), fields, row.eligibility());
    }

    private static ScalarValue intervalInstant(String value) {
        return ScalarValue.known(PARSER, JsonNodeFactory.instance.objectNode().put("type", "INSTANT").put("value", value).toString());
    }

    String evaluatorPlanDigest(VclCompiler compiler, RuleCompilationSnapshots.StartCheckpoint checkpoint,
            PreparedLookups lookups,
            java.util.function.Supplier<List<SpatialFeatureSnapshot>> circles, String bundleDigest) {
        var intervalNodes = evaluatorNodes("interval_union_window");
        var windowNodes = evaluatorNodes("window_aggregate");
        var sustainedNodes = evaluatorNodes("sustained");
        if ((!checkpoint.references().isEmpty() && intervalNodes.size() + windowNodes.size() != 1)
                || checkpoint.source().sources().size() != 1
                || !checkpoint.source().sources().getFirst().proof().qualifiesEventTime()) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        String planDigest;
        if (intervalNodes.size() == 1 && windowNodes.isEmpty() && sustainedNodes.isEmpty()) {
            String node = intervalNodes.getFirst();
            planDigest = lookups == null
                    ? intervalEvaluatorPlanDigest(compiler, node, bundleDigest)
                    : lookups.intervalEvaluatorPlanDigest(node, bundleDigest);
        } else if (!intervalNodes.isEmpty()) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        } else if (windowNodes.size() == 1 && sustainedNodes.isEmpty()) {
            String node = windowNodes.getFirst();
            var batch = lookups == null ? prepareJournalWindowBatch(compiler, node,
                    64L * VclWindowAggregateKernel.MAX_INPUTS, true) : lookups.journalAggregate(node);
            planDigest = batch.evaluatorPlanDigest();
        } else if (windowNodes.isEmpty() && sustainedNodes.size() == 1) {
            planDigest = sustainedEvaluatorPlanDigest(compiler, sustainedNodes.getFirst(), bundleDigest);
        } else if (windowNodes.isEmpty() && sustainedNodes.isEmpty()) {
            planDigest = sessionEvaluatorPlanDigest(compiler, circles, bundleDigest);
        } else {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        return planDigest;
    }

    private List<String> evaluatorNodes(String method) {
        var nodes = new ArrayList<String>();
        for (var node : document.spec().path("nodes")) {
            if (method.equals(node.path("method").textValue())) nodes.add(node.path("id").textValue());
        }
        return nodes;
    }

    String sustainedEvaluatorPlanDigest(VclCompiler compiler, String nodeId, String bundleDigest) {
        var compilation = compile(compiler);
        var methods = compilation.graph().stream().map(VclCompilation.Node::method).filter(method -> !"filter".equals(method)).toList();
        if (methods.size() != 3 || !java.util.Set.copyOf(methods).equals(java.util.Set.of("read_stream", "sustained", "emit_alert"))) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        sessionAlertIntent(nodeId, bundleDigest).orElseThrow(() -> new VclException("EVALUATOR_PROFILE_UNAVAILABLE", ""));
        var plan = VclSustainedPlan.compile(compilation, nodeId, new Metadata());
        var journal = journals.get(new SourceBindingSnapshots.Target(plan.binding(), plan.schemaRevision()));
        if (journal == null || plan.timing().requiredInputs().size() != 1) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        reauthorize();
        return VclCanonical.sha256(String.join("\n", "DIRECT_JOURNAL_SUSTAINED_EVALUATOR/1",
                DurableSustained.PROFILE, compilation.semanticDigest(), compilation.accessScopeDigest(),
                plan.digest(), plan.timing().digest(), journal.cutDigest()));
    }

    void observeSustained(VclSustainedPlan plan, String nodeId, JdbcRuleDeployments.Deployment deployment,
            JdbcKeyedStateStore store, JsonNode event, UUID holder, Runnable deploymentCheck) {
        var start = journals.get(new SourceBindingSnapshots.Target(plan.binding(), plan.schemaRevision()));
        if (start == null || !start.accountId().equals(deployment.account())) {
            throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        }
        var at = Instant.parse(event.path("eventTime").textValue());
        if (!deployment.includesWindow(at)) return;
        UUID id = UUID.fromString(event.path("inputEventId").textValue());
        var subject = identity(event, plan.subjectField(), id);
        if (subject == null) return;
        var sample = sustainedSample(event, id, at);
        if (plan.excludes(sample)) return;
        var journal = start.atCheckpoint(event.path("checkpoint").textValue());
        var key = sustainedKey(journal, deployment.epoch(), nodeId, subject);
        var durable = new DurableSustained(store, plan, key, deployment.bundleDigest(), () -> {
            reauthorize();
            deploymentCheck.run();
        }, (target, lease, transition, openedAt) -> store.commitReviewedInput(target, lease, transition, openedAt, deployment));
        durable.observe(sample, VclCanonical.sha256(VclCanonical.encode(event)), scope, holder,
                new DurableSustained.JournalPin(journal.inputGeneration(), journal.capturedHeadCheckpoint()),
                (from, through) -> sustainedHistory(journal, plan, key, from, through));
    }

    private List<VclSustainedPlan.Sample> sustainedHistory(SourceCaptureSnapshot journal, VclSustainedPlan plan,
            KeyedStateStore.Key key, Instant from, Instant through) {
        var rows = new ArrayList<VclSustainedPlan.Sample>();
        var until = through.plus(1, java.time.temporal.ChronoUnit.MICROS);
        long remaining = MAX_RETAINED_WINDOW_BYTES;
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
                    var subject = identity(event, plan.subjectField(), id);
                    if (subject != null && !key.subjectDigest().equals(subject)) continue;
                    var sample = sustainedSample(event, id, Instant.parse(event.path("eventTime").textValue()));
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

    private static VclSustainedPlan.Sample sustainedSample(JsonNode event, UUID id, Instant at) {
        return new VclSustainedPlan.Sample(id, at, Instant.parse(event.path("recordedAt").textValue()), observationFields(event, id));
    }

    /** Evaluates the immutable journal range for one subject without granting effect authority. */
    SustainedPreview previewDirectSustained(VclCompiler compiler, String nodeId, UUID previewEpoch, ScalarValue subject,
            VclWindowPlan.Window window, long byteBudget) {
        var plan = prepareSustained(compiler, nodeId);
        var journal = journals.get(new SourceBindingSnapshots.Target(plan.binding(), plan.schemaRevision()));
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        var key = sustainedKey(journal, previewEpoch, nodeId, VclWindowSessionKernel.identity(subject));
        return previewSustained(plan, journal, key, window, byteBudget);
    }

    private KeyedStateStore.Key sustainedKey(SourceCaptureSnapshot journal, UUID epoch, String nodeId, String subject) {
        return new KeyedStateStore.Key(journal.accountId(), UUID.fromString(document.spec().path("ruleId").textValue()),
                epoch, subject, VclCanonical.sha256("VCL_SUSTAINED_CURSOR/1\n" + nodeId + "\n" + journal.inputGeneration()));
    }

    SustainedPreview previewSustained(VclSustainedPlan plan, SourceCaptureSnapshot journal, KeyedStateStore.Key key,
            VclWindowPlan.Window window, long byteBudget) {
        if (byteBudget <= 0 || byteBudget > MAX_RETAINED_WINDOW_BYTES) {
            throw new VclException("WINDOW_INPUT_BYTE_BUDGET_INVALID", "");
        }
        if (journal == null || !journal.accountId().equals(key.accountId())) {
            throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        }
        var until = window.endExclusive().plus(1, java.time.temporal.ChronoUnit.MICROS);
        var rows = new ArrayList<VclSustainedPlan.Sample>();
        var ids = new HashSet<UUID>();
        String cut = journal.cutDigest();
        var fingerprints = new StringBuilder(String.join("\n", "RULE_SUSTAINED_JOURNAL/1", cut, plan.digest(),
                key.digest(), window.startInclusive().toString(), window.endExclusive().toString()));
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
                    var subject = identity(event, plan.subjectField(), id);
                    if (subject != null && !key.subjectDigest().equals(subject)) continue;
                    var sample = sustainedSample(event, id, Instant.parse(event.path("eventTime").textValue()));
                    if (plan.excludes(sample)) continue;
                    fingerprints.append('\n').append(VclCanonical.sha256(VclCanonical.encode(event)));
                    if (subject == null) {
                        if (unresolved == null) unresolved = id;
                        continue;
                    }
                    if (rows.size() >= VclDurationWitnessKernel.MAX_INPUTS) throw new VclException("WITNESS_INPUT_LIMIT", "");
                    rows.add(sample);
                }
                after = page.nextCheckpoint();
            } while (page.hasMore());
            from = chunkEnd;
        }
        var evaluation = plan.episodes(key, window, rows, scope);
        if (unresolved != null && !evaluation.result().value().isFailure()) {
            var evidence = List.of(unresolved);
            evaluation = new VclDurationWitnessKernel.Episodes(new VclDurationWitnessKernel.Result(
                    ScalarValue.unknown("WITNESS_SUBJECT_UNKNOWN", evidence), 0, null, null, evidence), List.of());
        }
        reauthorize();
        return new SustainedPreview(cut, VclCanonical.sha256(fingerprints.toString()), evaluation.result(), evaluation.occurrences());
    }

    public record SustainedPreview(String cutDigest, String inputDigest, VclDurationWitnessKernel.Result result,
            List<VclDurationWitnessKernel.Episode> episodes) {
        public SustainedPreview { episodes = List.copyOf(episodes); }
    }

    String sessionEvaluatorPlanDigest(VclCompiler compiler, java.util.function.Supplier<List<SpatialFeatureSnapshot>> circles,
            String bundleDigest) {
        var compilation = compile(compiler);
        var methods = compilation.graph().stream().map(VclCompilation.Node::method).filter(method -> !"filter".equals(method)).toList();
        boolean zone = methods.contains("zone_session");
        if (methods.size() != 5 || !java.util.Set.copyOf(methods).equals(java.util.Set.of("read_stream",
                zone ? "zone_session" : "window_session", "observed_within", "absent_within", "emit_alert"))) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        var absence = compilation.graph().stream().filter(node -> "absent_within".equals(node.method()))
                .findFirst().orElseThrow();
        sessionAlertIntent(absence.id(), bundleDigest).orElseThrow(() -> new VclException("EVALUATOR_PROFILE_UNAVAILABLE", ""));
        var plans = new ArrayList<VclSessionAbsenceKernel.Plan>();
        var metadata = new Metadata();
        if (zone) {
            var session = compilation.graph().stream().filter(node -> "zone_session".equals(node.method()))
                    .findFirst().orElseThrow();
            var input = VclJournalInputPlan.compile(compilation, session, metadata).source();
            var binding = metadata.binding(UUID.fromString(input.config().path("bindingId").textValue()),
                    input.config().path("schemaRevision").longValue())
                    .orElseThrow(() -> new VclException("SESSION_SOURCE_CHANGED", ""));
            var routing = com.voltcanary.vcl.VclSourceBindingProfile.occurrenceFields(binding.definition())
                    .orElseThrow(() -> new VclException("SESSION_OCCURRENCE_ROUTING_UNAVAILABLE", ""));
            if (!routing.subject().toString().equals(session.config().path("subjectFieldId").textValue())
                    || !routing.occurrence().toString().equals(session.config().path("occurrenceFieldId").textValue())) {
                throw new VclException("SESSION_OCCURRENCE_ROUTING_MISMATCH", "");
            }
            for (var geometry : circles.get()) {
                for (UUID id : geometry.circles().keySet()) {
                    plans.add(VclSessionAbsenceKernel.compile(compilation, absence.id(), metadata, null, geometry, id));
                }
            }
        } else {
            plans.add(VclSessionAbsenceKernel.compile(compilation, absence.id(), metadata, null));
        }
        if (plans.isEmpty()) throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        var digests = plans.stream().map(plan -> VclCanonical.sha256(String.join("\n",
                plan.sessionDigest(), plan.timing().digest(), plan.witness().digest()))).sorted().toList();
        reauthorize();
        return VclCanonical.sha256(String.join("\n", "DIRECT_JOURNAL_SESSION_EVALUATOR/1",
                compilation.semanticDigest(), compilation.accessScopeDigest(), String.join("\n", digests)));
    }

    Optional<DurableJournalWindow.AlertIntent> sessionAlertIntent(String nodeId, String bundleDigest) {
        for (var node : document.spec().path("nodes")) {
            if (!"emit_alert".equals(node.path("method").textValue())
                    || !nodeId.equals(node.at("/inputs/decisions").textValue())) continue;
            var config = node.path("config");
            if (!"RULE_SUBJECT_OCCURRENCE".equals(config.path("dedup").textValue())
                    || !policyPin.id().toString().equals(config.path("evidencePolicyId").textValue())) {
                throw new VclException("SESSION_ALERT_POLICY_UNSUPPORTED", "");
            }
            return Optional.of(new DurableJournalWindow.AlertIntent(bundleDigest, grantPin.id(), grantPin.revision(),
                    grantPin.snapshotDigest(), policyPin.id(), policyPin.snapshotDigest(), config.path("severity").textValue()));
        }
        return Optional.empty();
    }

    record SessionAbsenceInput(VclSessionAbsenceKernel.InputCut cut, VclSourceCoverage proof, long lastRelevantCheckpoint) { }

    SessionAbsenceInput sessionPreviewFacts(VclSessionAbsenceKernel.Plan plan, KeyedStateStore.Key key,
            VclWindowPlan.Window window, UUID proofId) {
        var source = plan.source();
        var journal = journals.get(new SourceBindingSnapshots.Target(source.id(), source.revision()));
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        var facts = readSessionAbsenceInput(plan, key, window.endExclusive().minus(1, java.time.temporal.ChronoUnit.MICROS),
                window.startInclusive(), window, journal.capturedHeadCheckpoint(), null, false);
        var proof = sessionPreviewProof(plan, window, proofId);
        return new SessionAbsenceInput(facts.cut(), proof, facts.lastRelevantCheckpoint());
    }

    VclSourceCoverage sessionPreviewProof(VclSessionAbsenceKernel.Plan plan, VclWindowPlan.Window window, UUID proofId) {
        if (proofId == null) return null;
        var source = plan.source();
        var journal = journals.get(new SourceBindingSnapshots.Target(source.id(), source.revision()));
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        return journal.coverage(proofId, window.startInclusive(), plan.timing().windowCloseDueAt(window.endExclusive()));
    }

    SessionAbsenceInput readSessionAbsenceInput(VclSessionAbsenceKernel.Plan plan, KeyedStateStore.Key key,
            Instant readEnd, Instant replayStart, VclWindowPlan.Window coverageWindow,
            String closingCheckpoint, UUID proofId, boolean speculative) {
        reauthorize();
        var source = plan.source();
        var journal = journals.get(new SourceBindingSnapshots.Target(source.id(), source.revision()));
        if (journal == null || !journal.accountId().equals(key.accountId())) {
            throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        }
        var closingCut = journal.atCheckpoint(closingCheckpoint);
        var proof = proofId == null ? null : closingCut.coverage(proofId, coverageWindow.startInclusive(),
                plan.timing().windowCloseDueAt(coverageWindow.endExclusive()), true);
        var input = speculative ? closingCut.atCurrentHead()
                : proof != null && proof.qualifiesEventTime() ? closingCut.forFinalization(proof) : closingCut;
        // The journal range is half-open. Include the actual close observation at microsecond precision.
        var until = readEnd.plus(1, java.time.temporal.ChronoUnit.MICROS);
        var observations = new ArrayList<VclSessionAbsenceKernel.Sample>();
        var eventIds = new HashSet<UUID>();
        UUID unresolved = null;
        UUID unresolvedFilter = null;
        UUID correctionRequired = null;
        long closedAtCheckpoint = VclObservationReadiness.checkpoint(closingCheckpoint);
        long lastRelevantCheckpoint = closedAtCheckpoint;
        long remainingBytes = MAX_RETAINED_WINDOW_BYTES;
        for (var from = replayStart; from.isBefore(until); ) {
            var chunkEnd = from.plus(com.voltcanary.vcl.VclCoverageSearch.MAX_INTERVAL);
            if (chunkEnd.isAfter(until)) chunkEnd = until;
            String after = input.generationStartCheckpoint();
            VclObservationPage page;
            do {
                page = input.readPage(after, 128, from, chunkEnd);
                remainingBytes -= page.bytes().length;
                if (remainingBytes < 0) throw new VclException("WINDOW_INPUT_BYTE_BUDGET_EXCEEDED", "");
                for (var event : page.observations()) {
                    UUID id = UUID.fromString(event.path("inputEventId").textValue());
                    if (!eventIds.add(id)) throw new VclException("WINDOW_INPUT_EVENT_DUPLICATE", "");
                    var subject = identity(event, plan.subjectField(), id);
                    var occurrence = identity(event, plan.occurrenceField(), id);
                    if ((subject != null && !subject.equals(key.subjectDigest()))
                            || (occurrence != null && !occurrence.equals(key.occurrenceDigest()))) continue;
                    var fields = observationFields(event, id);
                    var selection = plan.input().select(fields);
                    if (selection == VclJournalInputPlan.Selection.EXCLUDED) continue;
                    long checkpoint = VclObservationReadiness.checkpoint(event.path("checkpoint").textValue());
                    lastRelevantCheckpoint = Math.max(lastRelevantCheckpoint, checkpoint);
                    if (checkpoint > closedAtCheckpoint) {
                        if (correctionRequired == null) correctionRequired = id;
                    }
                    var eventTime = Instant.parse(event.path("eventTime").textValue());
                    if (selection == VclJournalInputPlan.Selection.UNKNOWN) {
                        if (unresolvedFilter == null && (!eventTime.isBefore(coverageWindow.startInclusive())
                                || checkpoint > closedAtCheckpoint)) unresolvedFilter = id;
                        continue;
                    }
                    if (subject == null || occurrence == null) {
                        if (unresolved == null && (!eventTime.isBefore(coverageWindow.startInclusive())
                                || checkpoint > closedAtCheckpoint)) {
                            unresolved = id;
                        }
                        continue;
                    }
                    if (observations.size() >= VclDurationWitnessKernel.MAX_INPUTS) {
                        throw new VclException("WITNESS_INPUT_LIMIT", "");
                    }
                    observations.add(new VclSessionAbsenceKernel.Sample(key, id, eventTime,
                            Instant.parse(event.path("recordedAt").textValue()), fields));
                }
                after = page.nextCheckpoint();
            } while (page.hasMore());
            from = chunkEnd;
        }
        reauthorize();
        return new SessionAbsenceInput(new VclSessionAbsenceKernel.InputCut(source.id(), source.revision(),
                input.snapshotId(), input.inputGeneration(), input.capturedHeadCheckpoint(), observations,
                unresolved, correctionRequired, unresolvedFilter), proof, lastRelevantCheckpoint);
    }

    private static Map<UUID, ScalarValue> observationFields(JsonNode event, UUID id) {
        var fields = new LinkedHashMap<UUID, ScalarValue>();
        event.path("fields").forEach(field -> fields.put(UUID.fromString(field.path("fieldId").textValue()),
                observationValue(field, id)));
        return fields;
    }

    private static String identity(JsonNode event, UUID field, UUID observation) {
        var value = observationValue(observationField(event, field.toString()), observation);
        return value.status() == ScalarValue.Status.OK && !value.isNull() ? VclWindowSessionKernel.identity(value) : null;
    }

    public void reauthorize() {
        reauthorize(false);
    }

    void verifyBundleOwners(com.voltcanary.analytics.vcl.CompilationBundle bundle) {
        reauthorize();
        bundle.verifyReviewedOwnerPins(grantPin, policyPin);
    }

    com.voltcanary.analytics.vcl.CompilationOwnerValidation bundleValidation(
            VclCompiler compiler, RuleCompilationSnapshots.PreparedStart prepared,
            List<com.voltcanary.vcl.VclCircleRevision> circles) {
        var compilation = prepared.compilation();
        var checkpoint = prepared.checkpoint();
        var sustained = evaluatorNodes("sustained");
        var windows = evaluatorNodes("window_aggregate");
        var intervals = evaluatorNodes("interval_union_window");
        var sessions = evaluatorNodes("window_session");
        var zones = evaluatorNodes("zone_session");
        if (zones.isEmpty() != circles.isEmpty()) throw new VclException("SPATIAL_REVISION_UNAVAILABLE", "");
        if (sustained.size() + windows.size() + intervals.size() + sessions.size() + zones.size() != 1
                || checkpoint.source().sources().size() != 1
                || (!sustained.isEmpty() || !sessions.isEmpty() || !zones.isEmpty()) && prepared.lookups() != null
                || compilation.dependencies().stream().filter(dep -> dep.kind() == VclMetadata.Resource.BINDING).count() != 1) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        String profile;
        String subjectField;
        long maxRows;
        long fanout;
        if (!sessions.isEmpty() || !zones.isEmpty()) {
            sessionEvaluatorPlanDigest(compiler, () -> circles.stream().map(circle -> {
                var shape = circle.geometry();
                var geometry = new SpatialFeatureSnapshot.Circle(circle.id(), circle.revision(),
                        new SpatialFeatureSnapshot.Point(shape.longitude(), shape.latitude()),
                        new java.math.BigDecimal(shape.radiusMetres()));
                return new SpatialFeatureSnapshot(sessionScope(), Map.of(circle.id(), geometry));
            }).toList(), checkpoint.digest());
            var sessionId = zones.isEmpty() ? sessions.getFirst() : zones.getFirst();
            var session = compilation.graph().stream().filter(node -> node.id().equals(sessionId))
                    .findFirst().orElseThrow().config();
            subjectField = session.path("subjectFieldId").textValue() + "\n" + session.path("occurrenceFieldId").textValue();
            profile = zones.isEmpty() ? com.voltcanary.analytics.vcl.DurableWindowSession.PROFILE
                    : com.voltcanary.analytics.vcl.DurableZoneSession.PROFILE;
            maxRows = VclDurationWitnessKernel.MAX_INPUTS;
            fanout = 1;
        } else if (!intervals.isEmpty()) {
            var node = intervals.getFirst();
            if (prepared.lookups() == null) {
                intervalEvaluatorPlanDigest(compilation, new Metadata(), null, node, checkpoint.digest());
            } else {
                prepared.lookups().intervalEvaluatorPlanDigest(node, checkpoint.digest());
            }
            subjectField = intervalOperator(compilation, node).config().path("subjectFieldId").textValue();
            profile = IntervalWindowSchedule.PROFILE;
            maxRows = VclIntervalUnionKernel.MAX_INPUTS;
            fanout = VclIntervalUnionPlan.MAX_WINDOW_FANOUT;
        } else if (windows.isEmpty()) {
            // The checkpoint digest is carried only in the validated alert intent.
            sustainedEvaluatorPlanDigest(compiler, sustained.getFirst(), checkpoint.digest());
            subjectField = prepareSustained(compiler, sustained.getFirst()).subjectField().toString();
            profile = DurableSustained.PROFILE;
            maxRows = VclDurationWitnessKernel.MAX_INPUTS;
            fanout = 1;
        } else {
            var batch = prepared.lookups() == null
                    ? prepareJournalWindowBatch(compilation, new Metadata(), null, windows.getFirst(),
                            64L * VclWindowAggregateKernel.MAX_INPUTS, true)
                    : prepared.lookups().journalAggregate(windows.getFirst());
            batch.evaluatorPlanDigest();
            subjectField = batch.subjectField;
            profile = DurableJournalWindow.PROFILE;
            maxRows = VclWindowAggregateKernel.MAX_INPUTS;
            fanout = batch.plan().windows().maximumFanout();
        }
        var input = checkpoint.source().sources().getFirst();
        var journal = journals.get(new SourceBindingSnapshots.Target(input.binding(), input.schemaRevision()));
        long work = Math.multiplyExact(Math.addExact(journal.retainedObservationCount(), maxRows), compilation.graph().size());
        work = Math.multiplyExact(work, fanout);
        var versions = new LinkedHashMap<String, String>();
        compilation.graph().forEach(node -> versions.put(node.method(), profile));
        var startPin = new ResourcePin(com.voltcanary.analytics.vcl.CompilationOwnerValidation.ResourceKind.RULE_START_CHECKPOINT,
                UUID.fromString(document.spec().path("ruleId").textValue()), 1, checkpoint.digest());
        var resources = new ArrayList<>(List.of(grantPin, policyPin, startPin));
        circles.forEach(circle -> resources.add(new ResourcePin(
                com.voltcanary.analytics.vcl.CompilationOwnerValidation.ResourceKind.SPATIAL_FEATURE,
                circle.id(), circle.revision(), circle.digest())));
        var execution = new com.voltcanary.analytics.vcl.CompilationOwnerValidation.ExecutionPins(grantPin.id(),
                Map.of(input.binding(), input.proof().inputGeneration()), checkpoint.source().boundary().toString(),
                VclCanonical.sha256(subjectField), checkpoint.digest(), resources,
                com.voltcanary.analytics.vcl.Wgs84ScalarKernel.VERSION, versions);
        var checks = compilation.dependencies().stream().map(dependency -> {
            boolean binding = dependency.kind() == VclMetadata.Resource.BINDING;
            return new com.voltcanary.analytics.vcl.CompilationOwnerValidation.DependencyCheck(dependency, true, true,
                    binding ? com.voltcanary.analytics.vcl.CompilationOwnerValidation.IndexReadiness.NOT_REQUIRED
                            : com.voltcanary.analytics.vcl.CompilationOwnerValidation.IndexReadiness.READY,
                    binding ? journal.cutDigest() : checkpoint.digest());
        }).toList();
        long workBudget = intervals.isEmpty() ? 64 * maxRows : 1024 * maxRows;
        var cost = new com.voltcanary.analytics.vcl.CompilationOwnerValidation.Cost(
                fanout * KeyedStateStore.MAX_STATE_BYTES, 64L * KeyedStateStore.MAX_STATE_BYTES, work, workBudget);
        reauthorize(prepared.lookups() != null);
        return new com.voltcanary.analytics.vcl.CompilationOwnerValidation(compilation.documentDigest(),
                compilation.accessScopeDigest(), checks, execution, cost);
    }

    private void reauthorize(boolean sealedLookups) {
        if (bindings != null && !ownerScope.equals(bindings.accessScopeDigest())
                || !sealedLookups && datasets != null && !ownerScope.equals(datasets.accessScopeDigest())) {
            throw new VclException("ACCESS_SCOPE_CHANGED", "");
        }
        if (sealedLookups && datasets != null) datasets.reauthorizeSealedSchema();
        for (var journal : journals.values()) {
            if (!ownerScope.equals(journal.accessScopeDigest())) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        }
        if (!grantPin.equals(grant.pin())) throw new VclException("AUTOMATION_GRANT_CHANGED", "");
        if (!policyPin.equals(policy.pin())) throw new VclException("EVIDENCE_POLICY_CHANGED", "");
    }

    private final class Metadata implements VclMetadata {
        private final java.util.Set<UUID> preparedDatasets;
        private final String compilationScope;

        private Metadata() {
            this(java.util.Set.of(), scope);
        }

        private Metadata(java.util.Set<UUID> preparedDatasets, String compilationScope) {
            this.preparedDatasets = java.util.Set.copyOf(preparedDatasets);
            this.compilationScope = compilationScope;
        }

        private void reauthorizeInputs() {
            reauthorize(!preparedDatasets.isEmpty());
        }

        @Override public String accessScopeDigest() {
            reauthorizeInputs();
            return compilationScope;
        }

        @Override public Optional<Snapshot> dataset(UUID id, long revision) {
            if (datasets == null || !datasetTargets.contains(new NativeDatasetSnapshots.Target(id, revision))) return Optional.empty();
            reauthorizeInputs();
            var value = preparedDatasets.contains(id) ? datasets.sealedSchema(id, revision) : datasets.dataset(id, revision);
            value.ifPresent(snapshot -> requireFields(Resource.DATASET, snapshot));
            if (!preparedDatasets.contains(id)) return value;
            return value.map(snapshot -> {
                var definition = (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.definition();
                definition.remove(List.of("generation", "dataVersion"));
                definition.putObject("runtimeProjection").put("supportsAsOf", true);
                return new Snapshot(id, revision, definition);
            });
        }

        @Override public Optional<Snapshot> binding(UUID id, long revision) {
            var target = new SourceBindingSnapshots.Target(id, revision);
            if (!bindingTargets.contains(target)) return Optional.empty();
            reauthorizeInputs();
            var owner = journals.isEmpty() ? bindings : journals.get(target);
            var value = owner.binding(id, revision);
            value.ifPresent(snapshot -> requireFields(Resource.BINDING, snapshot));
            return value;
        }

        @Override public boolean permits(Resource kind, UUID id) {
            reauthorizeInputs();
            return kind == Resource.EXECUTION_GRANT && grantPin.id().equals(id)
                    || kind == Resource.EVIDENCE_POLICY && policyPin.id().equals(id);
        }

        private void requireFields(Resource kind, Snapshot snapshot) {
            // read_stream exposes the entire source schema. No field may enter the IR outside the exact grant.
            // This initial profile requires all exposed schema fields, including lookup schema fields.
            var fields = new java.util.ArrayList<UUID>();
            for (var field : snapshot.definition().path("fields")) fields.add(UUID.fromString(field.path("id").asText()));
            grant.requireFields(kind, snapshot.id(), snapshot.revision(), fields);
        }
    }

    @Override public String toString() { return "RuleCompilationSnapshot[stage=STATIC_TYPED_IR]"; }
}
