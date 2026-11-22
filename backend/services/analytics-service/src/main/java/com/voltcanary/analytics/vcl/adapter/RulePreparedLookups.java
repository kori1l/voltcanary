package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclIntervalUnionPlan;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.analytics.vcl.VclWindowAggregateKernel;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.CorrectionCatchup;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.IntervalFinalization;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.LookupPin;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.Metadata;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.ReferenceStartPin;
import com.voltcanary.analytics.vcl.adapter.RuleCompilationSnapshot.WindowPreview;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompilation;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationReadiness;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RulePreparedLookups {

    private final RuleCompilationSnapshot snapshot;
    final VclCompilation compilation;
    final Map<String, LookupPin> pins;
    final NativeAsOfOwner owner;
    final NativeAsOfOwner.Transport transport;

    RulePreparedLookups(
        RuleCompilationSnapshot snapshot,
        VclCompilation compilation,
        Map<String, LookupPin> pins,
        NativeAsOfOwner owner,
        NativeAsOfOwner.Transport transport
    ) {
        this.snapshot = snapshot;
        this.compilation = compilation;
        this.pins = Map.copyOf(pins);
        this.owner = owner;
        this.transport = transport;
    }

    public VclCompilation compilation() {
        return compilation;
    }

    Map<String, ReferenceStartPin> referenceStartPins() {
        var result = new java.util.TreeMap<String, ReferenceStartPin>();
        pins.forEach((node, pin) ->
            result.put(
                node,
                new ReferenceStartPin(
                    VclCanonical.encode(pin.selection().query().json()),
                    pin.selection().intervalField(),
                    VclCanonical.sha256(VclCanonical.encode(pin.manifest()))
                )
            )
        );
        return Map.copyOf(result);
    }

    public JsonNode lookup(String nodeId, Map<UUID, JsonNode> event, Instant eventTime) {
        var pin = pins.get(nodeId);
        if (pin == null) throw new VclException("ASOF_OPERATOR_REQUIRED", "");
        var node = compilation
            .graph()
            .stream()
            .filter(candidate -> candidate.id().equals(nodeId))
            .findFirst()
            .orElseThrow();
        snapshot.reauthorize(true);
        var result = owner.lookup(
            transport,
            node,
            pin.selection().query(),
            pin.selection().intervalField(),
            event,
            eventTime,
            pin.manifest(),
            RuleCompilationSnapshot.referenceReadDeadline()
        );
        snapshot.reauthorize(true);
        return result;
    }

    public WindowPreview previewIntervalUnion(
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        long byteBudget
    ) {
        return snapshot.previewJournalIntervalUnion(
            compilation,
            metadata(),
            this,
            nodeId,
            subjectDigest,
            window,
            byteBudget,
            null,
            null,
            window.endExclusive()
        );
    }

    public WindowPreview previewWindowAggregate(
        String nodeId,
        String subject,
        VclWindowPlan.Window window,
        long byteBudget
    ) {
        return journalAggregate(nodeId).preview(subject, window, byteBudget, true);
    }

    WindowPreview previewWindowAggregate(
        String nodeId,
        ScalarValue subject,
        VclWindowPlan.Window window,
        long byteBudget
    ) {
        return journalAggregate(nodeId).preview(subject, window, byteBudget);
    }

    String sourceCutDigest() {
        return snapshot.sourceCutDigest();
    }

    CorrectionCatchup correctionCatchup(
        String nodeId,
        SourceBindingSnapshots.Target target,
        VclWindowPlan.Window window,
        String originalCheckpoint,
        boolean aggregate
    ) {
        var source = snapshot.journals.get(target);
        if (source == null) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
        var latest = source.atCurrentHead();
        var until = window.endExclusive();
        if (!aggregate) {
            var plan = VclIntervalUnionPlan.compile(compilation, nodeId, metadata());
            var definition = latest
                .binding(target.id(), target.revision())
                .orElseThrow(() -> new VclException("SOURCE_CAPTURE_UNAVAILABLE", ""))
                .definition();
            until = plan.reportingHorizon(window, definition);
        }
        var timing = VclTimingPlan.compile(compilation);
        long lastRelevant = VclObservationReadiness.checkpoint(originalCheckpoint);
        long remainingBytes = RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES;
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
                    lastRelevant = VclObservationReadiness.checkpoint(
                        event.path("checkpoint").textValue()
                    );
                }
            }
            after = page.nextCheckpoint();
        } while (page.hasMore());
        return new CorrectionCatchup(
            latest.inputGeneration(),
            VclObservationReadiness.checkpoint(latest.capturedHeadCheckpoint()),
            lastRelevant
        );
    }

    RuleJournalWindowBatch journalAggregate(String nodeId) {
        return snapshot.prepareJournalWindowBatch(
            compilation,
            metadata(),
            this,
            nodeId,
            64L * VclWindowAggregateKernel.MAX_INPUTS,
            true
        );
    }

    Optional<IntervalFinalization> finalizeIntervalUnion(
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        Map<String, ReferenceStartPin> expected,
        long minimumCheckpoint,
        java.util.function.BiFunction<Instant, Instant, UUID> discoverProof
    ) {
        if (!referenceStartPins().equals(expected)) throw new VclException(
            "INTERVAL_REFERENCE_CHANGED",
            ""
        );
        return snapshot.finalizeJournalIntervalUnion(
            compilation,
            metadata(),
            this,
            nodeId,
            subjectDigest,
            window,
            window.endExclusive(),
            minimumCheckpoint,
            discoverProof
        );
    }

    Optional<IntervalFinalization> proveIntervalPrefix(
        String nodeId,
        String subjectDigest,
        VclWindowPlan.Window window,
        Instant through,
        Map<String, ReferenceStartPin> expected,
        long minimumCheckpoint,
        java.util.function.BiFunction<Instant, Instant, UUID> discoverProof
    ) {
        if (!referenceStartPins().equals(expected)) throw new VclException(
            "INTERVAL_REFERENCE_CHANGED",
            ""
        );
        RuleCompilationSnapshot.requireIntervalLowerBound(compilation, nodeId, window, through);
        return snapshot.finalizeJournalIntervalUnion(
            compilation,
            metadata(),
            this,
            nodeId,
            subjectDigest,
            window,
            through,
            minimumCheckpoint,
            discoverProof
        );
    }

    void stageIntervalWindows(
        String nodeId,
        SourceBindingSnapshots.Target target,
        JdbcRuleDeployments.Deployment deployment,
        JdbcKeyedStateStore store,
        JsonNode event,
        UUID holder
    ) {
        snapshot.stageIntervalWindows(
            compilation,
            metadata(),
            this,
            nodeId,
            target,
            deployment,
            store,
            event,
            holder
        );
    }

    String intervalPlanDigest(String nodeId) {
        return VclIntervalUnionPlan.compile(compilation, nodeId, metadata()).digest();
    }

    String intervalEvaluatorPlanDigest(String nodeId, String bundleDigest) {
        return snapshot.intervalEvaluatorPlanDigest(
            compilation,
            metadata(),
            this,
            nodeId,
            bundleDigest
        );
    }

    JournalWindowDecision intervalDecision(String nodeId, ScalarValue subject) {
        return JournalWindowDecision.compileTyped(
            compilation,
            RuleCompilationSnapshot.intervalOperator(compilation, nodeId),
            snapshot.intervalDecimalPolicy(),
            metadata(),
            subject
        );
    }

    private Metadata metadata() {
        var datasets = new HashSet<UUID>();
        pins.values().forEach(pin ->
            datasets.add(
                UUID.fromString(pin.selection().query().spec().at("/target/datasetId").textValue())
            )
        );
        return snapshot.new Metadata(datasets, compilation.accessScopeDigest());
    }

    void verifyManifests() {
        long deadline = RuleCompilationSnapshot.referenceReadDeadline();
        for (var node : compilation.graph()) {
            var pin = pins.get(node.id());
            if (pin == null) continue;
            var keys = new ArrayList<UUID>();
            node.config()
                .path("keyPairs")
                .forEach(pair -> keys.add(UUID.fromString(pair.path("recordFieldId").textValue())));
            var current = owner.inspect(
                transport,
                pin.selection().query(),
                pin.selection().intervalField(),
                keys,
                deadline
            );
            if (!pin.manifest().equals(current)) throw new VclException(
                "INTERVAL_REFERENCE_CHANGED",
                ""
            );
        }
        snapshot.reauthorize(true);
    }
}
