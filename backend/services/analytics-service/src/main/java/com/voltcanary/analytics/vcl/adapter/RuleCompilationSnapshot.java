package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.CompilationOwnerValidation.ResourcePin;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarDecimalPolicy;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.SpatialFeatureSnapshot;
import com.voltcanary.analytics.vcl.VclDurationWitnessKernel;
import com.voltcanary.analytics.vcl.VclIntervalUnionPlan;
import com.voltcanary.analytics.vcl.VclJournalInputPlan;
import com.voltcanary.analytics.vcl.VclSessionAbsenceKernel;
import com.voltcanary.analytics.vcl.VclSustainedPlan;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.analytics.vcl.VclWindowAggregateKernel;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceCoverage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Document-bound static metadata composition, deliberately distinct from OWNER_VALIDATED_IR. */
public final class RuleCompilationSnapshot {

    static final VclParser PARSER = new VclParser();
    static final long MAX_RETAINED_WINDOW_BYTES = 8L * 1024 * 1024;
    final VclDocument document;
    private final SourceBindingSnapshot bindings;
    final Map<SourceBindingSnapshots.Target, SourceCaptureSnapshot> journals;
    private final NativeDatasetSnapshot datasets;
    final AutomationGrantSnapshot grant;
    final EvidencePolicySnapshot policy;
    private final List<SourceBindingSnapshots.Target> bindingTargets;
    private final List<NativeDatasetSnapshots.Target> datasetTargets;
    final ResourcePin grantPin;
    final ResourcePin policyPin;
    final String ownerScope;
    final String scope;

    RuleCompilationSnapshot(
        VclDocument document,
        SourceBindingSnapshot bindings,
        NativeDatasetSnapshot datasets,
        AutomationGrantSnapshot grant,
        EvidencePolicySnapshot policy,
        List<SourceBindingSnapshots.Target> bindingTargets,
        List<NativeDatasetSnapshots.Target> datasetTargets,
        Map<SourceBindingSnapshots.Target, SourceCaptureSnapshot> journals
    ) {
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
        ownerScope = journals.isEmpty()
            ? bindings.accessScopeDigest()
            : journals.values().iterator().next().accessScopeDigest();
        String ownerPins =
            ownerScope + "\n" + grantPin.snapshotDigest() + "\n" + policyPin.snapshotDigest();
        scope = VclCanonical.sha256(
            journals.isEmpty()
                ? "RULE_STATIC_OWNERS/1\n" + ownerPins
                : "RULE_JOURNAL_OWNERS/1\n" +
                      ownerPins +
                      "\n" +
                      String.join(
                          "\n",
                          journals
                              .values()
                              .stream()
                              .map(SourceCaptureSnapshot::cutDigest)
                              .sorted()
                              .toList()
                      )
        );
        reauthorize();
    }

    /**
     * The compiler sees the same closed schema/registry as other consumers, backed by real owner
     * APIs.
     */
    public VclCompilation compile(VclCompiler compiler) {
        var compilation = compiler.compile(document, new Metadata());
        reauthorize();
        return compilation;
    }

    public com.voltcanary.vcl.VclSourceCoverage coverage(
        UUID binding,
        long revision,
        UUID proof,
        Instant from,
        Instant until
    ) {
        var journal = journals.get(new SourceBindingSnapshots.Target(binding, revision));
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        new Metadata()
            .binding(binding, revision)
            .orElseThrow(() -> new VclException("SOURCE_BINDING_UNAVAILABLE", ""));
        var result = journal.coverage(proof, from, until);
        reauthorize();
        return result;
    }

    public record SourceStartProof(UUID binding, long schemaRevision, UUID proofId) {}

    public record SourceStartCheckpoint(
        Instant from,
        Instant boundary,
        List<SourceCaptureSnapshot.CoveredCheckpoint> sources,
        String digest
    ) {
        public SourceStartCheckpoint {
            sources = List.copyOf(sources);
        }
    }

    /** Build the source part of a start checkpoint from the exact journal cuts. */
    public SourceStartCheckpoint sourceStartCheckpoint(
        Instant from,
        Instant boundary,
        List<SourceStartProof> proofs
    ) {
        if (
            from == null ||
            boundary == null ||
            !from.isBefore(boundary) ||
            from.getNano() % 1000 != 0 ||
            boundary.getNano() % 1000 != 0 ||
            journals.isEmpty() ||
            proofs == null ||
            proofs.size() != journals.size()
        ) {
            throw new VclException("SOURCE_START_CHECKPOINT_INVALID", "");
        }
        var selections = new java.util.HashMap<SourceBindingSnapshots.Target, UUID>();
        for (var proof : proofs) {
            if (
                proof == null ||
                proof.binding() == null ||
                proof.proofId() == null ||
                selections.putIfAbsent(
                    new SourceBindingSnapshots.Target(proof.binding(), proof.schemaRevision()),
                    proof.proofId()
                ) != null
            ) {
                throw new VclException("SOURCE_START_CHECKPOINT_INVALID", "");
            }
        }
        if (!selections.keySet().equals(journals.keySet())) {
            throw new VclException("SOURCE_START_CHECKPOINT_INVALID", "");
        }
        reauthorize();
        var entries = journals
            .entrySet()
            .stream()
            .sorted(
                Map.Entry.comparingByKey(
                    java.util.Comparator.comparing(
                        SourceBindingSnapshots.Target::id
                    ).thenComparingLong(SourceBindingSnapshots.Target::revision)
                )
            )
            .toList();
        var checkpoints = new ArrayList<SourceCaptureSnapshot.CoveredCheckpoint>();
        var payload = JsonNodeFactory.instance
            .objectNode()
            .put("profile", "RULE_SOURCE_START/1")
            .put("scope", scope)
            .put("documentDigest", VclCanonical.sha256(VclCanonical.encode(document.json())))
            .put("from", from.toString())
            .put("boundary", boundary.toString());
        var sourcePins = payload.putArray("sources");
        for (var entry : entries) {
            var checkpoint = entry
                .getValue()
                .coveredCheckpoint(selections.get(entry.getKey()), from, boundary);
            checkpoints.add(checkpoint);
            var covered = checkpoint.proof();
            var evidence = covered.evidence();
            var pin = sourcePins
                .addObject()
                .put("binding", checkpoint.binding().toString())
                .put("revision", checkpoint.schemaRevision())
                .put("cut", checkpoint.cut().digest())
                .put("head", checkpoint.cut().head())
                .put("proof", covered.proofId().toString())
                .put("snapshot", covered.snapshotId().toString())
                .put("generation", covered.inputGeneration().toString())
                .put("requiredCheckpoint", covered.requiredCheckpoint())
                .put("producerEpoch", evidence.position().epoch().toString())
                .put("sequence", evidence.position().sequence())
                .put("coveredFrom", evidence.from().toString())
                .put("coveredUntil", evidence.until().toString());
            if (covered.acceptedAt() != null) pin.put(
                "acceptedAt",
                covered.acceptedAt().toString()
            );
            if (evidence.eventTimeBasis() != null) pin.put(
                "eventTimeBasis",
                evidence.eventTimeBasis()
            );
            if (!evidence.parts().isEmpty()) {
                var parts = pin.putArray("coverageParts");
                evidence.parts().forEach(id -> parts.add(id.toString()));
            }
        }
        reauthorize();
        return new SourceStartCheckpoint(
            from,
            boundary,
            checkpoints,
            VclCanonical.sha256(VclCanonical.encode(payload))
        );
    }

    public void verifySourceStartCheckpoint(SourceStartCheckpoint expected) {
        if (expected == null) throw new VclException("SOURCE_START_CHECKPOINT_INVALID", "");
        var proofs = expected
            .sources()
            .stream()
            .map(source ->
                new SourceStartProof(
                    source.binding(),
                    source.schemaRevision(),
                    source.proof().proofId()
                )
            )
            .toList();
        if (!expected.equals(sourceStartCheckpoint(expected.from(), expected.boundary(), proofs))) {
            throw new VclException("SOURCE_START_CHECKPOINT_CHANGED", "");
        }
    }

    void verifyCompilationStart(RuleCompilationSnapshots.PreparedStart prepared) {
        verifySourceStartCheckpoint(prepared.checkpoint().source());
        if (prepared.lookups() != null) prepared.lookups().verifyManifests();
    }

    public record LookupSelection(VclDocument query, UUID intervalField) {}

    public record ReferenceStartPin(
        String canonicalQuery,
        UUID intervalField,
        String manifestDigest
    ) {}

    record LookupPin(LookupSelection selection, JsonNode manifest) {}

    record CorrectionCatchup(UUID generation, long scannedHead, long lastRelevant) {}

    static long referenceReadDeadline() {
        return System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
    }

    /**
     * Compile a bounded historical rule read with an owner-verified sealed query for each lookup
     * node.
     */
    RulePreparedLookups prepareLookups(
        VclCompiler compiler,
        NativeAsOfOwner owner,
        String authorization,
        Map<String, LookupSelection> selections
    ) {
        return prepareLookups(compiler, owner, owner.user(authorization), selections);
    }

    RulePreparedLookups prepareLookups(
        VclCompiler compiler,
        NativeAsOfOwner owner,
        NativeAsOfOwner.Transport transport,
        Map<String, LookupSelection> selections
    ) {
        var pins = new java.util.TreeMap<String, LookupPin>();
        var preparedDatasets = new HashSet<UUID>();
        long deadline = referenceReadDeadline();
        reauthorize();
        for (var declaration : document.spec().path("nodes")) {
            if (!"lookup_asof".equals(declaration.path("method").textValue())) continue;
            String nodeId = declaration.path("id").textValue();
            var selection = selections.get(nodeId);
            if (selection == null || datasets == null) throw new VclException(
                "ASOF_SNAPSHOT_REQUIRED",
                ""
            );
            var config = declaration.path("config");
            var target = config.path("target");
            UUID dataset = UUID.fromString(target.path("datasetId").textValue());
            long revision = target.path("schemaRevision").longValue();
            var query = selection.query().spec();
            if (!target.equals(query.path("target"))) throw new VclException(
                "ASOF_PROJECTION_CONFLICT",
                ""
            );
            var keys = new ArrayList<UUID>();
            config
                .path("keyPairs")
                .forEach(pair -> keys.add(UUID.fromString(pair.path("recordFieldId").textValue())));
            var expected = new HashSet<>(keys);
            expected.add(selection.intervalField());
            config
                .path("select")
                .forEach(field -> expected.add(UUID.fromString(field.textValue())));
            var selected = new HashSet<UUID>();
            query.path("select").forEach(field -> selected.add(UUID.fromString(field.textValue())));
            if (!expected.equals(selected)) throw new VclException("ASOF_PROJECTION_CONFLICT", "");
            grant.requireFields(
                VclMetadata.Resource.DATASET,
                dataset,
                revision,
                List.copyOf(selected)
            );
            var description = datasets
                .description(dataset, revision)
                .orElseThrow(() -> new VclException("DATASET_UNAVAILABLE", ""));
            var manifest = owner.inspect(
                transport,
                selection.query(),
                selection.intervalField(),
                keys,
                deadline
            );
            if (
                !description.schemaDigest().equals(manifest.path("schemaDigest").textValue()) ||
                !ownerScope.equals(manifest.path("accessScopeDigest").textValue())
            ) {
                throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
            }
            if (
                pins.putIfAbsent(nodeId, new LookupPin(selection, manifest)) != null
            ) throw new VclException("ASOF_PROJECTION_CONFLICT", "");
            preparedDatasets.add(dataset);
        }
        if (pins.isEmpty() || !pins.keySet().equals(selections.keySet())) throw new VclException(
            "ASOF_OPERATOR_REQUIRED",
            ""
        );
        var manifestSet = JsonNodeFactory.instance.objectNode();
        pins.forEach((id, pin) ->
            manifestSet
                .putObject(id)
                .put("intervalField", pin.selection().intervalField().toString())
                .set("manifest", pin.manifest())
        );
        String lookupScope = VclCanonical.sha256(
            scope + "\nRULE_ASOF/1\n" + VclCanonical.encode(manifestSet)
        );
        var compiled = compiler.compile(document, new Metadata(preparedDatasets, lookupScope));
        reauthorize(true);
        return new RulePreparedLookups(this, compiled, pins, owner, transport);
    }

    /**
     * Verify static types and exact grants before checking the source owner's runtime readiness.
     */
    public VclCompilation compileWithReadySources(VclCompiler compiler) {
        var compilation = compile(compiler);
        if (journals.isEmpty()) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        for (var journal : journals.values()) {
            journal.requireRuntimeReady();
        }
        reauthorize();
        return compilation;
    }

    /** Diagnostic arithmetic with explicitly unknown upstream coverage and finalization. */
    public record WindowPreview(String inputDigest, ScalarValue localValue) {
        public String upstreamCoverage() {
            return "UNKNOWN";
        }

        public String eventTimeProvenance() {
            return "UNQUALIFIED";
        }

        public boolean finalized() {
            return false;
        }
    }

    static JsonNode observationField(JsonNode observation, String id) {
        for (var field : observation.path("fields")) {
            if (id.equals(field.path("fieldId").textValue())) return field;
        }
        throw new VclException("WINDOW_INPUT_FIELD_MISSING", "");
    }

    static ScalarValue observationValue(JsonNode field, UUID eventId) {
        if ("UNKNOWN".equals(field.path("knowledge").textValue())) {
            return ScalarValue.unknown("AGGREGATE_INPUT_UNKNOWN", List.of(eventId));
        }
        var literal = JsonNodeFactory.instance
            .objectNode()
            .put("type", field.path("type").textValue());
        if ("DECIMAL".equals(field.path("type").textValue())) literal.put(
            "unit",
            field.path("unit").textValue()
        );
        literal.set("value", field.path("value"));
        return ScalarValue.known(PARSER, literal.toString());
    }

    /** Locally complete journal pages. Upstream coverage remains the page owner's UNKNOWN. */
    public record RetainedWindowInput(
        String cutDigest,
        String inputDigest,
        List<VclObservationPage> pages,
        int observationCount,
        long encodedBytes
    ) {
        public RetainedWindowInput {
            pages = List.copyOf(pages);
        }
    }

    /**
     * Bound assignment and reduction input for one pure window operator over a direct journal
     * source.
     */
    public RuleJournalWindowBatch prepareJournalWindowBatch(
        VclCompiler compiler,
        String nodeId,
        long membershipBudget
    ) {
        return prepareJournalWindowBatch(compiler, nodeId, membershipBudget, false);
    }

    RuleJournalWindowBatch prepareJournalWindowBatch(
        VclCompiler compiler,
        String nodeId,
        long membershipBudget,
        boolean ranged
    ) {
        return prepareJournalWindowBatch(
            compile(compiler),
            new Metadata(),
            null,
            nodeId,
            membershipBudget,
            ranged
        );
    }

    RuleJournalWindowBatch prepareJournalWindowBatch(
        VclCompilation compilation,
        Metadata owner,
        RulePreparedLookups prepared,
        String nodeId,
        long membershipBudget,
        boolean ranged
    ) {
        if (membershipBudget <= 0) throw new VclException("WINDOW_MEMBERSHIP_BUDGET_INVALID", "");
        if (journals.isEmpty()) throw new VclException("WINDOW_JOURNAL_REQUIRED", "");
        var node = compilation
            .graph()
            .stream()
            .filter(
                candidate ->
                    candidate.id().equals(nodeId) && candidate.method().equals("window_aggregate")
            )
            .findFirst()
            .orElseThrow(() -> new VclException("WINDOW_OPERATOR_REQUIRED", ""));
        var input = intervalInput(compilation, node, prepared);
        var source = input.source();
        var config = source.config();
        var target = new SourceBindingSnapshots.Target(
            UUID.fromString(config.path("bindingId").asText()),
            config.path("schemaRevision").longValue()
        );
        var journal = journals.get(target);
        if (journal == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        long rows = journal.retainedObservationCount();
        if (!ranged && rows > VclWindowAggregateKernel.MAX_INPUTS) throw new VclException(
            "WINDOW_BATCH_INPUT_LIMIT",
            ""
        );

        var decimal = document.spec().path("decimalPolicy");
        var policy = new ScalarDecimalPolicy(
            decimal.path("precision").intValue(),
            decimal.path("scale").intValue(),
            ScalarDecimalPolicy.Rounding.valueOf(decimal.path("rounding").asText())
        );
        var plan = VclWindowAggregateKernel.Plan.compile(compilation, nodeId, policy);
        long memberships = ranged ? membershipBudget : rows * plan.windows().maximumFanout();
        if (memberships > membershipBudget) throw new VclException(
            "WINDOW_MEMBERSHIP_BUDGET_EXCEEDED",
            ""
        );
        owner.reauthorizeInputs();
        var aggregate = node.config();
        return new RuleJournalWindowBatch(
            this,
            plan,
            memberships,
            journal,
            VclTimingPlan.compile(compilation),
            aggregate.path("subjectFieldId").textValue(),
            aggregate.path("valueFieldId").textValue(),
            compilation,
            node,
            policy,
            input,
            prepared,
            owner
        );
    }

    record SessionObservation<T>(KeyedStateStore.Key key, T value, boolean included) {}

    record UnresolvedSessionInput(
        String subject,
        String occurrence,
        Instant at,
        long maximumAgeUs,
        VclTimingPlan timing
    ) {}

    static Optional<UnresolvedSessionInput> unresolvedSessionInput(
        JsonNode event,
        UUID subjectField,
        UUID occurrenceField,
        long maximumAgeUs,
        VclTimingPlan timing,
        VclJournalInputPlan input
    ) {
        return RuleSessionPreparation.unresolvedSessionInput(
            event,
            subjectField,
            occurrenceField,
            maximumAgeUs,
            timing,
            input
        );
    }

    RuleSessionBatch prepareWindowSession(VclCompiler compiler, String nodeId) {
        return new RuleSessionPreparation(this).prepareWindowSession(compiler, nodeId);
    }

    <T> List<T> sessionReplayRows(
        SourceCaptureSnapshot journal,
        JdbcRuleDeployments.Deployment deployment,
        Instant from,
        Instant through,
        java.util.function.Function<JsonNode, Optional<T>> route
    ) {
        return new RuleSessionPreparation(this).sessionReplayRows(
            journal,
            deployment,
            from,
            through,
            route
        );
    }

    RuleZoneBatch prepareZoneSession(VclCompiler compiler, String nodeId) {
        return new RuleSessionPreparation(this).prepareZoneSession(compiler, nodeId);
    }

    VclSessionAbsenceKernel.Plan prepareSessionAbsence(
        VclCompiler compiler,
        String nodeId,
        VclDurationWitnessKernel.HoldContract hold,
        SpatialFeatureSnapshot geometry,
        UUID zone
    ) {
        return new RuleSessionPreparation(this).prepareSessionAbsence(
            compiler,
            nodeId,
            hold,
            geometry,
            zone
        );
    }

    String sessionScope() {
        return scope;
    }

    List<UUID> retainedWindowReferences(JsonNode result, Instant windowStart, Instant now) {
        return new RuleSessionPreparation(this).retainedWindowReferences(result, windowStart, now);
    }

    public VclSustainedPlan prepareSustained(VclCompiler compiler, String nodeId) {
        return new RuleSustainedPreparation(this).prepareSustained(compiler, nodeId);
    }

    public VclIntervalUnionPlan prepareIntervalUnion(VclCompiler compiler, String nodeId) {
        return new RuleIntervalPreparation(this).prepareIntervalUnion(compiler, nodeId);
    }

    public WindowPreview previewDirectIntervalUnion(
        VclCompiler compiler,
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        long byteBudget
    ) {
        return new RuleIntervalPreparation(this).previewDirectIntervalUnion(
            compiler,
            nodeId,
            subjectDigest,
            window,
            byteBudget
        );
    }

    String sourceCutDigest() {
        return new RuleIntervalPreparation(this).sourceCutDigest();
    }

    record IntervalFinalization(
        WindowPreview result,
        VclSourceCoverage proof,
        String cutDigest,
        String checkpoint,
        Instant measuredThrough
    ) {}

    void stageIntervalWindows(
        VclCompiler compiler,
        String nodeId,
        SourceBindingSnapshots.Target target,
        JdbcRuleDeployments.Deployment deployment,
        JdbcKeyedStateStore store,
        JsonNode event,
        UUID holder
    ) {
        new RuleIntervalPreparation(this).stageIntervalWindows(
            compiler,
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
        new RuleIntervalPreparation(this).stageIntervalWindows(
            compilation,
            owner,
            prepared,
            nodeId,
            target,
            deployment,
            store,
            event,
            holder
        );
    }

    private static Instant intervalBound(VclIntervalUnionPlan.Row row, UUID field) {
        return RuleIntervalPreparation.intervalBound(row, field);
    }

    static VclWindowPlan.Window correctionRange(
        JsonNode event,
        SourceCaptureSnapshot journal,
        SourceBindingSnapshots.Target target,
        boolean interval
    ) {
        return RuleIntervalPreparation.correctionRange(event, journal, target, interval);
    }

    private static VclWindowPlan.Window requireIntervalReport(
        VclIntervalUnionPlan.Row row,
        com.voltcanary.vcl.VclSourceBindingProfile.IntervalFields contract,
        Instant eventTime
    ) {
        return RuleIntervalPreparation.requireIntervalReport(row, contract, eventTime);
    }

    JournalWindowDecision intervalDecision(
        VclCompiler compiler,
        String nodeId,
        ScalarValue subject
    ) {
        return new RuleIntervalPreparation(this).intervalDecision(compiler, nodeId, subject);
    }

    String intervalEvaluatorPlanDigest(VclCompiler compiler, String nodeId, String bundleDigest) {
        return new RuleIntervalPreparation(this).intervalEvaluatorPlanDigest(
            compiler,
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
        return new RuleIntervalPreparation(this).intervalEvaluatorPlanDigest(
            compilation,
            owner,
            prepared,
            nodeId,
            bundleDigest
        );
    }

    static VclCompilation.Node intervalOperator(VclCompilation compilation, String nodeId) {
        return RuleIntervalPreparation.intervalOperator(compilation, nodeId);
    }

    ScalarDecimalPolicy intervalDecimalPolicy() {
        return new RuleIntervalPreparation(this).intervalDecimalPolicy();
    }

    Optional<IntervalFinalization> finalizeDirectIntervalUnion(
        VclCompiler compiler,
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        long minimumCheckpoint,
        java.util.function.BiFunction<Instant, Instant, UUID> discoverProof
    ) {
        return new RuleIntervalPreparation(this).finalizeDirectIntervalUnion(
            compiler,
            nodeId,
            subjectDigest,
            window,
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
        return new RuleIntervalPreparation(this).proveDirectIntervalPrefix(
            compiler,
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
        RuleIntervalPreparation.requireIntervalLowerBound(compilation, nodeId, window, through);
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
        return new RuleIntervalPreparation(this).finalizeJournalIntervalUnion(
            compilation,
            owner,
            prepared,
            nodeId,
            subjectDigest,
            window,
            measuredThrough,
            minimumCheckpoint,
            discoverProof
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
        return new RuleIntervalPreparation(this).previewJournalIntervalUnion(
            compilation,
            owner,
            prepared,
            nodeId,
            subjectDigest,
            window,
            byteBudget,
            covered,
            reportingHorizon,
            measuredThrough
        );
    }

    record IntervalInput(VclCompilation.Node source, List<VclCompilation.Node> steps) {}

    static IntervalInput intervalInput(
        VclCompilation compilation,
        VclCompilation.Node operator,
        RulePreparedLookups prepared
    ) {
        return RuleIntervalPreparation.intervalInput(compilation, operator, prepared);
    }

    Map<String, java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>> intervalFilters(
        VclCompilation compilation,
        IntervalInput input,
        Metadata owner
    ) {
        return new RuleIntervalPreparation(this).intervalFilters(compilation, input, owner);
    }

    static List<VclIntervalUnionPlan.Row> expandIntervalInput(
        RulePreparedLookups prepared,
        List<VclCompilation.Node> steps,
        VclSustainedPlan.Sample sample,
        int limit,
        StringBuilder fingerprints,
        Map<String, java.util.function.Function<VclIntervalUnionPlan.Row, ScalarValue>> filters
    ) {
        return RuleIntervalPreparation.expandIntervalInput(
            prepared,
            steps,
            sample,
            limit,
            fingerprints,
            filters
        );
    }

    private static void projectIntervalLookup(
        RulePreparedLookups prepared,
        VclCompilation.Node lookup,
        VclIntervalUnionPlan.Row row,
        Instant eventTime,
        List<VclIntervalUnionPlan.Row> projected,
        StringBuilder fingerprints,
        int limit
    ) {
        RuleIntervalPreparation.projectIntervalLookup(
            prepared,
            lookup,
            row,
            eventTime,
            projected,
            fingerprints,
            limit
        );
    }

    private static VclIntervalUnionPlan.Row mergeIntervalLookup(
        VclCompilation.Node lookup,
        VclIntervalUnionPlan.Row row,
        JsonNode outcome,
        String unresolved
    ) {
        return RuleIntervalPreparation.mergeIntervalLookup(lookup, row, outcome, unresolved);
    }

    private static ScalarValue intervalInstant(String value) {
        return RuleIntervalPreparation.intervalInstant(value);
    }

    String evaluatorPlanDigest(
        VclCompiler compiler,
        RuleCompilationSnapshots.StartCheckpoint checkpoint,
        RulePreparedLookups lookups,
        java.util.function.Supplier<List<SpatialFeatureSnapshot>> circles,
        String bundleDigest
    ) {
        var intervalNodes = evaluatorNodes("interval_union_window");
        var windowNodes = evaluatorNodes("window_aggregate");
        var sustainedNodes = evaluatorNodes("sustained");
        if (
            (!checkpoint.references().isEmpty() &&
                intervalNodes.size() + windowNodes.size() != 1) ||
            checkpoint.source().sources().size() != 1 ||
            !checkpoint.source().sources().getFirst().proof().qualifiesEventTime()
        ) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        String planDigest;
        if (intervalNodes.size() == 1 && windowNodes.isEmpty() && sustainedNodes.isEmpty()) {
            String node = intervalNodes.getFirst();
            planDigest =
                lookups == null
                    ? intervalEvaluatorPlanDigest(compiler, node, bundleDigest)
                    : lookups.intervalEvaluatorPlanDigest(node, bundleDigest);
        } else if (!intervalNodes.isEmpty()) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        } else if (windowNodes.size() == 1 && sustainedNodes.isEmpty()) {
            String node = windowNodes.getFirst();
            var batch =
                lookups == null
                    ? prepareJournalWindowBatch(
                          compiler,
                          node,
                          64L * VclWindowAggregateKernel.MAX_INPUTS,
                          true
                      )
                    : lookups.journalAggregate(node);
            planDigest = batch.evaluatorPlanDigest();
        } else if (windowNodes.isEmpty() && sustainedNodes.size() == 1) {
            planDigest = sustainedEvaluatorPlanDigest(
                compiler,
                sustainedNodes.getFirst(),
                bundleDigest
            );
        } else if (windowNodes.isEmpty() && sustainedNodes.isEmpty()) {
            planDigest = sessionEvaluatorPlanDigest(compiler, circles, bundleDigest);
        } else {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        return planDigest;
    }

    List<String> evaluatorNodes(String method) {
        var nodes = new ArrayList<String>();
        for (var node : document.spec().path("nodes")) {
            if (method.equals(node.path("method").textValue())) nodes.add(
                node.path("id").textValue()
            );
        }
        return nodes;
    }

    String sustainedEvaluatorPlanDigest(VclCompiler compiler, String nodeId, String bundleDigest) {
        return new RuleSustainedPreparation(this).sustainedEvaluatorPlanDigest(
            compiler,
            nodeId,
            bundleDigest
        );
    }

    void observeSustained(
        VclSustainedPlan plan,
        String nodeId,
        JdbcRuleDeployments.Deployment deployment,
        JdbcKeyedStateStore store,
        JsonNode event,
        UUID holder,
        Runnable deploymentCheck
    ) {
        new RuleSustainedPreparation(this).observeSustained(
            plan,
            nodeId,
            deployment,
            store,
            event,
            holder,
            deploymentCheck
        );
    }

    private List<VclSustainedPlan.Sample> sustainedHistory(
        SourceCaptureSnapshot journal,
        VclSustainedPlan plan,
        KeyedStateStore.Key key,
        Instant from,
        Instant through
    ) {
        return new RuleSustainedPreparation(this).sustainedHistory(
            journal,
            plan,
            key,
            from,
            through
        );
    }

    static VclSustainedPlan.Sample sustainedSample(JsonNode event, UUID id, Instant at) {
        return RuleSustainedPreparation.sustainedSample(event, id, at);
    }

    /** Evaluates the immutable journal range for one subject without granting effect authority. */
    SustainedPreview previewDirectSustained(
        VclCompiler compiler,
        String nodeId,
        UUID previewEpoch,
        ScalarValue subject,
        VclWindowPlan.Window window,
        long byteBudget
    ) {
        return new RuleSustainedPreparation(this).previewDirectSustained(
            compiler,
            nodeId,
            previewEpoch,
            subject,
            window,
            byteBudget
        );
    }

    private KeyedStateStore.Key sustainedKey(
        SourceCaptureSnapshot journal,
        UUID epoch,
        String nodeId,
        String subject
    ) {
        return new RuleSustainedPreparation(this).sustainedKey(journal, epoch, nodeId, subject);
    }

    SustainedPreview previewSustained(
        VclSustainedPlan plan,
        SourceCaptureSnapshot journal,
        KeyedStateStore.Key key,
        VclWindowPlan.Window window,
        long byteBudget
    ) {
        return new RuleSustainedPreparation(this).previewSustained(
            plan,
            journal,
            key,
            window,
            byteBudget
        );
    }

    public record SustainedPreview(
        String cutDigest,
        String inputDigest,
        VclDurationWitnessKernel.Result result,
        List<VclDurationWitnessKernel.Episode> episodes
    ) {
        public SustainedPreview {
            episodes = List.copyOf(episodes);
        }
    }

    String sessionEvaluatorPlanDigest(
        VclCompiler compiler,
        java.util.function.Supplier<List<SpatialFeatureSnapshot>> circles,
        String bundleDigest
    ) {
        return new RuleSessionPreparation(this).sessionEvaluatorPlanDigest(
            compiler,
            circles,
            bundleDigest
        );
    }

    Optional<DurableJournalWindow.AlertIntent> sessionAlertIntent(
        String nodeId,
        String bundleDigest
    ) {
        return new RuleSessionPreparation(this).sessionAlertIntent(nodeId, bundleDigest);
    }

    record SessionAbsenceInput(
        VclSessionAbsenceKernel.InputCut cut,
        VclSourceCoverage proof,
        long lastRelevantCheckpoint
    ) {}

    SessionAbsenceInput sessionPreviewFacts(
        VclSessionAbsenceKernel.Plan plan,
        KeyedStateStore.Key key,
        VclWindowPlan.Window window,
        UUID proofId
    ) {
        return new RuleSessionPreparation(this).sessionPreviewFacts(plan, key, window, proofId);
    }

    VclSourceCoverage sessionPreviewProof(
        VclSessionAbsenceKernel.Plan plan,
        VclWindowPlan.Window window,
        UUID proofId
    ) {
        return new RuleSessionPreparation(this).sessionPreviewProof(plan, window, proofId);
    }

    SessionAbsenceInput readSessionAbsenceInput(
        VclSessionAbsenceKernel.Plan plan,
        KeyedStateStore.Key key,
        Instant readEnd,
        Instant replayStart,
        VclWindowPlan.Window coverageWindow,
        String closingCheckpoint,
        UUID proofId,
        boolean speculative
    ) {
        return new RuleSessionPreparation(this).readSessionAbsenceInput(
            plan,
            key,
            readEnd,
            replayStart,
            coverageWindow,
            closingCheckpoint,
            proofId,
            speculative
        );
    }

    static Map<UUID, ScalarValue> observationFields(JsonNode event, UUID id) {
        return RuleSessionPreparation.observationFields(event, id);
    }

    static String identity(JsonNode event, UUID field, UUID observation) {
        return RuleSessionPreparation.identity(event, field, observation);
    }

    public void reauthorize() {
        reauthorize(false);
    }

    void verifyBundleOwners(com.voltcanary.analytics.vcl.CompilationBundle bundle) {
        reauthorize();
        bundle.verifyReviewedOwnerPins(grantPin, policyPin);
    }

    com.voltcanary.analytics.vcl.CompilationOwnerValidation bundleValidation(
        VclCompiler compiler,
        RuleCompilationSnapshots.PreparedStart prepared,
        List<com.voltcanary.vcl.VclCircleRevision> circles
    ) {
        return new RuleBundleValidation(this).bundleValidation(compiler, prepared, circles);
    }

    void reauthorize(boolean sealedLookups) {
        if (
            (bindings != null && !ownerScope.equals(bindings.accessScopeDigest())) ||
            (!sealedLookups && datasets != null && !ownerScope.equals(datasets.accessScopeDigest()))
        ) {
            throw new VclException("ACCESS_SCOPE_CHANGED", "");
        }
        if (sealedLookups && datasets != null) datasets.reauthorizeSealedSchema();
        for (var journal : journals.values()) {
            if (!ownerScope.equals(journal.accessScopeDigest())) throw new VclException(
                "ACCESS_SCOPE_CHANGED",
                ""
            );
        }
        if (!grantPin.equals(grant.pin())) throw new VclException("AUTOMATION_GRANT_CHANGED", "");
        if (!policyPin.equals(policy.pin())) throw new VclException("EVIDENCE_POLICY_CHANGED", "");
    }

    final class Metadata implements VclMetadata {

        private final java.util.Set<UUID> preparedDatasets;
        private final String compilationScope;

        Metadata() {
            this(java.util.Set.of(), scope);
        }

        Metadata(java.util.Set<UUID> preparedDatasets, String compilationScope) {
            this.preparedDatasets = java.util.Set.copyOf(preparedDatasets);
            this.compilationScope = compilationScope;
        }

        void reauthorizeInputs() {
            reauthorize(!preparedDatasets.isEmpty());
        }

        @Override
        public String accessScopeDigest() {
            reauthorizeInputs();
            return compilationScope;
        }

        @Override
        public Optional<Snapshot> dataset(UUID id, long revision) {
            if (
                datasets == null ||
                !datasetTargets.contains(new NativeDatasetSnapshots.Target(id, revision))
            ) return Optional.empty();
            reauthorizeInputs();
            var value = preparedDatasets.contains(id)
                ? datasets.sealedSchema(id, revision)
                : datasets.dataset(id, revision);
            value.ifPresent(snapshot -> requireFields(Resource.DATASET, snapshot));
            if (!preparedDatasets.contains(id)) return value;
            return value.map(snapshot -> {
                var definition =
                    (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.definition();
                definition.remove(List.of("generation", "dataVersion"));
                definition.putObject("runtimeProjection").put("supportsAsOf", true);
                return new Snapshot(id, revision, definition);
            });
        }

        @Override
        public Optional<Snapshot> binding(UUID id, long revision) {
            var target = new SourceBindingSnapshots.Target(id, revision);
            if (!bindingTargets.contains(target)) return Optional.empty();
            reauthorizeInputs();
            var owner = journals.isEmpty() ? bindings : journals.get(target);
            var value = owner.binding(id, revision);
            value.ifPresent(snapshot -> requireFields(Resource.BINDING, snapshot));
            return value;
        }

        @Override
        public boolean permits(Resource kind, UUID id) {
            reauthorizeInputs();
            return (
                (kind == Resource.EXECUTION_GRANT && grantPin.id().equals(id)) ||
                (kind == Resource.EVIDENCE_POLICY && policyPin.id().equals(id))
            );
        }

        private void requireFields(Resource kind, Snapshot snapshot) {
            // read_stream exposes the entire source schema. No field may enter the IR outside the
            // exact grant.
            // This initial profile requires all exposed schema fields, including lookup schema
            // fields.
            var fields = new java.util.ArrayList<UUID>();
            for (var field : snapshot.definition().path("fields"))
                fields.add(UUID.fromString(field.path("id").asText()));
            grant.requireFields(kind, snapshot.id(), snapshot.revision(), fields);
        }
    }

    @Override
    public String toString() {
        return "RuleCompilationSnapshot[stage=STATIC_TYPED_IR]";
    }
}
