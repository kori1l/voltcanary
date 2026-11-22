package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.SpatialFeatureSnapshot;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/** Restores the exact reviewed start inputs using scoped machine owner reads. */
@Component
final class WorkerStartCheckpoints {
    private final JdbcCompilationBundleStore bundles;
    private final JdbcRuleDeployments deployments;
    private final WorkerSourceCaptureSnapshots captures;
    private final WorkerSourceCaptureReads metadata;
    private final WorkerDatasetAsOfReads references;
    private final WorkerGrantReads grants;
    private final WorkerPolicyReads policies;
    private final NativeAsOfOwner asOf;
    private final WorkerCircleReads circles;

    WorkerStartCheckpoints(
            DataSource source,
            WorkerSourceCaptureSnapshots captures,
            WorkerSourceCaptureReads metadata,
            WorkerDatasetAsOfReads references,
            WorkerGrantReads grants,
            WorkerPolicyReads policies,
            NativeAsOfOwner asOf,
            WorkerCircleReads circles) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        this.captures = captures;
        this.metadata = metadata;
        this.references = references;
        this.grants = grants;
        this.policies = policies;
        this.asOf = asOf;
        this.circles = circles;
    }

    RuleCompilationSnapshots.StartCheckpoint restore(
            UUID account, UUID epoch, VclCompiler compiler, long inputBudget) {
        var candidate =
                deployments
                        .find(account, epoch)
                        .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!candidate.permitsOwnerInspection() || candidate.start() == null || inputBudget <= 0) {
            throw new VclException("START_CHECKPOINT_UNAVAILABLE", "");
        }
        return restore(candidate, compiler, inputBudget).checkpoint();
    }

    List<JdbcRuleDeployments.Deployment> pending(int limit, int offset) {
        return deployments.pendingEvaluatorReadiness(limit, offset);
    }

    RuleCompilationSnapshots.PreparedStart restoreWindowReferences(
            JdbcRuleDeployments.Deployment candidate, VclCompiler compiler) {
        return restoreWindowReferences(candidate, compiler, java.util.Map.of());
    }

    RuleCompilationSnapshots.PreparedStart restoreWindowReferences(
            JdbcRuleDeployments.Deployment candidate,
            VclCompiler compiler,
            java.util.Map<String, RuleCompilationSnapshot.ReferenceStartPin> override) {
        var restored = restore(candidate, compiler, Long.MAX_VALUE);
        if (restored.intervalNodes().size() + restored.windowNodes().size() != 1
                || restored.lookups() == null) {
            throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
        }
        if (override.isEmpty())
            return new RuleCompilationSnapshots.PreparedStart(
                    restored.checkpoint(), restored.lookups(), restored.lookups().compilation());
        if (!restored.checkpoint().references().keySet().equals(override.keySet())) {
            throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
        }
        var lookup =
                restored.snapshot()
                        .prepareLookups(
                                compiler,
                                asOf,
                                references.transport(candidate.account(), candidate.epoch()),
                                RuleStartCheckpointRecipe.selections(override));
        if (!lookup.referenceStartPins().equals(override))
            throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
        return new RuleCompilationSnapshots.PreparedStart(
                restored.checkpoint(), lookup, lookup.compilation());
    }

    void acknowledge(JdbcRuleDeployments.Deployment candidate, VclCompiler compiler) {
        if (candidate.state() != JdbcRuleDeployments.State.ACTIVATING
                || candidate.start() == null) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        var restored = restore(candidate, compiler, Long.MAX_VALUE);
        var checkpoint = restored.checkpoint();
        String planDigest =
                restored.snapshot()
                        .evaluatorPlanDigest(
                                compiler,
                                checkpoint,
                                restored.lookups(),
                                () -> circleSnapshots(candidate, restored),
                                candidate.bundleDigest());
        String readiness =
                VclCanonical.sha256(
                        String.join(
                                "\n", candidate.bundleDigest(), checkpoint.digest(), planDigest));
        deployments.acknowledgeEvaluatorReadiness(
                candidate,
                new JdbcRuleDeployments.EvaluatorReadiness(
                        candidate.bundleDigest(), checkpoint.digest(), readiness));
    }

    private List<SpatialFeatureSnapshot> circleSnapshots(
            JdbcRuleDeployments.Deployment candidate, Restored restored) {
        if (restored.circles().isEmpty() || restored.circles().size() > 16) {
            throw new VclException("EVALUATOR_PROFILE_UNAVAILABLE", "");
        }
        return restored.circles().stream()
                .map(
                        pin -> {
                            var current =
                                    circles.current(
                                            candidate,
                                            pin.id(),
                                            pin.revision(),
                                            restored.snapshot().sessionScope());
                            if (!current.circles().containsKey(pin.id()))
                                throw new VclException("SPATIAL_REVISION_CHANGED", "");
                            return current;
                        })
                .toList();
    }

    private record CirclePin(UUID id, long revision) {}

    private record Restored(
            RuleCompilationSnapshots.StartCheckpoint checkpoint,
            RuleCompilationSnapshot snapshot,
            List<String> windowNodes,
            List<String> intervalNodes,
            List<CirclePin> circles,
            RulePreparedLookups lookups) {}

    private Restored restore(
            JdbcRuleDeployments.Deployment candidate, VclCompiler compiler, long inputBudget) {
        UUID account = candidate.account();
        UUID epoch = candidate.epoch();
        var inputs = deployments.readStartRecipe(account, epoch).inputs();
        var bundle =
                bundles.find(account, candidate.bundle())
                        .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        var parser = new VclParser();
        var payload = bundle.json();
        var compiled = payload.path("compilation");
        if (!candidate.bundleDigest().equals(bundle.digest())
                || !inputs.documentDigest().equals(compiled.path("documentDigest").textValue())
                || !candidate.start().boundary().equals(inputs.boundary())
                || !candidate.start().checkpointDigest().equals(inputs.checkpointDigest())) {
            throw new VclException("START_CHECKPOINT_CHANGED", "");
        }
        var document = parser.parse(compiled.path("canonicalDocument").textValue());
        var sourceTargets = new java.util.LinkedHashSet<SourceBindingSnapshots.Target>();
        var datasetTargets = new java.util.LinkedHashSet<NativeDatasetSnapshots.Target>();
        var policyIds = new java.util.LinkedHashSet<UUID>();
        var windowNodes = new java.util.ArrayList<String>();
        var intervalNodes = new java.util.ArrayList<String>();
        for (var node : compiled.path("graph")) {
            switch (node.path("method").asText()) {
                case "read_stream" ->
                        sourceTargets.add(
                                new SourceBindingSnapshots.Target(
                                        UUID.fromString(node.at("/config/bindingId").textValue()),
                                        node.at("/config/schemaRevision").longValue()));
                case "lookup_asof" ->
                        datasetTargets.add(
                                new NativeDatasetSnapshots.Target(
                                        UUID.fromString(
                                                node.at("/config/target/datasetId").textValue()),
                                        node.at("/config/target/schemaRevision").longValue()));
                case "emit_alert" ->
                        policyIds.add(
                                UUID.fromString(node.at("/config/evidencePolicyId").textValue()));
                case "window_aggregate" -> windowNodes.add(node.path("id").textValue());
                case "interval_union_window" -> intervalNodes.add(node.path("id").textValue());
                default -> {}
            }
        }
        if (!sourceTargets.equals(inputs.cuts().keySet())
                || sourceTargets.isEmpty()
                || sourceTargets.size() > 16
                || datasetTargets.size() > 16
                || policyIds.size() != 1) {
            throw new VclException("START_CHECKPOINT_CHANGED", "");
        }
        var journals = new LinkedHashMap<SourceBindingSnapshots.Target, SourceCaptureSnapshot>();
        long remaining = inputBudget;
        for (var target : sourceTargets) {
            var journal =
                    captures.capture(
                            account,
                            epoch,
                            target.id(),
                            target.revision(),
                            inputs.cuts().get(target));
            remaining -= journal.retainedObservationCount();
            if (remaining < 0) throw new VclException("JOURNAL_INPUT_BUDGET_EXCEEDED", "");
            journals.put(target, journal);
        }
        var datasets = new LinkedHashMap<NativeDatasetSnapshots.Target, VclDatasetDescription>();
        datasetTargets.forEach(
                target ->
                        datasets.put(
                                target,
                                metadata.datasetDescription(
                                        account, epoch, target.id(), target.revision())));
        var nativeData =
                datasets.isEmpty()
                        ? null
                        : new NativeDatasetSnapshot(
                                datasets,
                                target ->
                                        metadata.datasetDescription(
                                                account, epoch, target.id(), target.revision()));
        UUID policy = policyIds.iterator().next();
        var grant =
                new AutomationGrantSnapshot(
                        grants.read(account, epoch), () -> grants.read(account, epoch));
        var evidence =
                new EvidencePolicySnapshot(
                        policies.read(account, epoch, policy),
                        () -> policies.read(account, epoch, policy));
        var snapshot =
                new RuleCompilationSnapshot(
                        document,
                        null,
                        nativeData,
                        grant,
                        evidence,
                        List.copyOf(sourceTargets),
                        List.copyOf(datasetTargets),
                        journals);
        var prepared =
                RuleCompilationSnapshots.prepareStart(
                        snapshot,
                        compiler,
                        asOf,
                        references.transport(account, epoch),
                        inputs.from(),
                        inputs.boundary(),
                        inputs.proofs(),
                        inputs.references());
        var result = prepared.checkpoint();
        bundle.verifyCompiledDependencies(prepared.compilation());
        result.verifyBundleInputs(bundle);
        if (!result.digest().equals(inputs.checkpointDigest())
                || deployments.find(account, epoch).filter(candidate::equals).isEmpty()) {
            throw new VclException("START_CHECKPOINT_CHANGED", "");
        }
        var spatial = new java.util.ArrayList<CirclePin>();
        for (var pin : payload.at("/ownerValidation/execution/resourceRevisions")) {
            if ("SPATIAL_FEATURE".equals(pin.path("kind").textValue())) {
                spatial.add(
                        new CirclePin(
                                UUID.fromString(pin.path("id").textValue()),
                                pin.path("revision").longValue()));
            }
        }
        return new Restored(
                result,
                snapshot,
                List.copyOf(windowNodes),
                List.copyOf(intervalNodes),
                List.copyOf(spatial),
                prepared.lookups());
    }
}
