package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Composes authenticated owner reads for static RULE compilation; it supplies no execution attestation. */
@Component
@RequiredArgsConstructor
public final class RuleCompilationSnapshots {
    private final NativeDatasetSnapshots datasets;
    private final SourceBindingSnapshots bindings;
    private final AutomationGrantSnapshots grants;
    private final EvidencePolicySnapshots policies;
    private final SourceCaptureSnapshots captures;
    private final NativeAsOfOwner asOfOwner;

    /** Targets come only from the closed, parsed document; never accept a caller's metadata or attestations. */
    public RuleCompilationSnapshot capture(String authorization, UUID actor, UUID account, VclDocument document) {
        return capture(authorization, actor, account, document, 0, Map.of());
    }

    public RuleCompilationSnapshot.PreparedLookups prepareLookups(String authorization, UUID actor, UUID account,
            VclDocument document, VclCompiler compiler, Map<String, RuleCompilationSnapshot.LookupSelection> selections) {
        return capture(authorization, actor, account, document).prepareLookups(compiler, asOfOwner, authorization, selections);
    }

    RuleCompilationSnapshot.PreparedLookups prepareLookups(RuleCompilationSnapshot snapshot, VclCompiler compiler,
            String authorization, Map<String, RuleCompilationSnapshot.LookupSelection> selections) {
        return snapshot.prepareLookups(compiler, asOfOwner, authorization, selections);
    }

    RuleCompilationSnapshot captureJournalCut(String authorization, UUID actor, UUID account, VclDocument document,
            SourceBindingSnapshots.Target target, SourceCaptureSnapshot.Cut cut) {
        return capture(authorization, actor, account, document, Long.MAX_VALUE, Map.of(target, cut));
    }

    public record StartCheckpoint(RuleCompilationSnapshot.SourceStartCheckpoint source,
            Map<String, RuleCompilationSnapshot.ReferenceStartPin> references, String documentDigest,
            String compilationDigest, String digest) {
        public StartCheckpoint {
            references = Map.copyOf(references);
        }

        void verifyBundleInputs(com.voltcanary.analytics.vcl.CompilationBundle bundle) {
            var generations = new LinkedHashMap<UUID, UUID>();
            for (var input : source.sources()) {
                if (generations.putIfAbsent(input.binding(), input.proof().inputGeneration()) != null) {
                    throw new VclException("BUNDLE_START_INPUTS_CHANGED", "");
                }
            }
            bundle.verifyStartInputs(source.boundary(), generations);
            bundle.verifyStartCheckpoint(digest);
        }
    }

    /** Capture the original source cuts and sealed reference queries under the same RULE authority. */
    StartCheckpoint prepareStartCheckpoint(String authorization, UUID actor, UUID account, VclDocument document,
            VclCompiler compiler, long inputBudget, Instant from, Instant boundary,
            List<RuleCompilationSnapshot.SourceStartProof> proofs,
            Map<String, RuleCompilationSnapshot.LookupSelection> selections,
            com.voltcanary.analytics.vcl.CompilationBundle bundle) {
        var snapshot = captureJournal(authorization, actor, account, document, inputBudget);
        var prepared = prepareStart(snapshot, compiler, asOfOwner, asOfOwner.user(authorization),
                from, boundary, proofs, selections);
        bundle.verifyCompiledDependencies(prepared.compilation());
        prepared.checkpoint().verifyBundleInputs(bundle);
        snapshot.verifyBundleOwners(bundle);
        return prepared.checkpoint();
    }

    JdbcRuleDeployments.Deployment cutover(String authorization, UUID actor, UUID account,
            JdbcRuleDeployments deployments, UUID predecessor, UUID nextEpoch, VclDocument document,
            VclCompiler compiler, long inputBudget, StartCheckpoint checkpoint,
            com.voltcanary.analytics.vcl.CompilationBundle bundle, Runnable verifyReview, boolean finish) {
        if (checkpoint == null || checkpoint.source() == null) throw new VclException("START_CHECKPOINT_INVALID", "");
        var start = new JdbcRuleDeployments.CutoverStart(predecessor, checkpoint.source().boundary(), checkpoint.digest());
        var recipe = RuleStartCheckpointRecipe.from(checkpoint);
        return deployments.cutover(account, nextEpoch, start, recipe, (previous, next, boundary) -> {
            if (!next.actor().equals(actor)) throw new VclException("OWNER_AUTHENTICATION_REQUIRED", "");
            verifiedStartSnapshot(authorization, actor, account, document, compiler, inputBudget, checkpoint, bundle)
                    .verifyBundleOwners(bundle);
            verifyReview.run();
        }, finish);
    }

    public StartCheckpoint restoreStartCheckpoint(String authorization, UUID actor, UUID account,
            JdbcRuleDeployments deployments, UUID epoch, VclDocument document, VclCompiler compiler, long inputBudget) {
        var deployment = deployments.find(account, epoch)
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!deployment.actor().equals(actor)
                || !deployment.acceptsExistingWork() && deployment.state() != JdbcRuleDeployments.State.ACTIVATING
                || deployment.start() == null
                || inputBudget <= 0) throw new VclException("START_CHECKPOINT_UNAVAILABLE", "");
        var inputs = deployments.readStartRecipe(account, epoch).inputs();
        if (!deployment.start().boundary().equals(inputs.boundary())
                || !deployment.start().checkpointDigest().equals(inputs.checkpointDigest())
                || !VclCanonical.documentDigest(document).equals(inputs.documentDigest())) {
            throw new VclException("START_CHECKPOINT_CHANGED", "");
        }
        var snapshot = capture(authorization, actor, account, document, inputBudget, inputs.cuts());
        var fresh = startCheckpoint(snapshot, compiler, authorization, inputs.from(), inputs.boundary(),
                inputs.proofs(), inputs.references());
        if (!fresh.digest().equals(inputs.checkpointDigest())
                || deployments.find(account, epoch).filter(deployment::equals).isEmpty()) {
            throw new VclException("START_CHECKPOINT_CHANGED", "");
        }
        return fresh;
    }

    /** Renew exact historic inputs after a review or restart, without selecting newer owner snapshots. */
    RuleCompilationSnapshot verifiedStartSnapshot(String authorization, UUID actor, UUID account, VclDocument document,
            VclCompiler compiler, long inputBudget, StartCheckpoint expected,
            com.voltcanary.analytics.vcl.CompilationBundle bundle) {
        if (expected == null || expected.source() == null || inputBudget <= 0) {
            throw new VclException("START_CHECKPOINT_INVALID", "");
        }
        var cuts = new LinkedHashMap<SourceBindingSnapshots.Target, SourceCaptureSnapshot.Cut>();
        var proofs = new java.util.ArrayList<RuleCompilationSnapshot.SourceStartProof>();
        for (var source : expected.source().sources()) {
            if (cuts.putIfAbsent(new SourceBindingSnapshots.Target(source.binding(), source.schemaRevision()), source.cut()) != null) {
                throw new VclException("START_CHECKPOINT_INVALID", "");
            }
            proofs.add(new RuleCompilationSnapshot.SourceStartProof(source.binding(), source.schemaRevision(), source.proof().proofId()));
        }
        var parser = new VclParser();
        var selections = new LinkedHashMap<String, RuleCompilationSnapshot.LookupSelection>();
        expected.references().forEach((node, pin) -> selections.put(node,
                new RuleCompilationSnapshot.LookupSelection(parser.parse(pin.canonicalQuery()), pin.intervalField())));
        var snapshot = capture(authorization, actor, account, document, inputBudget, cuts);
        var fresh = prepareStart(snapshot, compiler, asOfOwner, asOfOwner.user(authorization),
                expected.source().from(), expected.source().boundary(), proofs, selections);
        if (!expected.equals(fresh.checkpoint())) throw new VclException("START_CHECKPOINT_CHANGED", "");
        bundle.verifyCompiledDependencies(fresh.compilation());
        return snapshot;
    }

    private StartCheckpoint startCheckpoint(RuleCompilationSnapshot snapshot, VclCompiler compiler, String authorization,
            Instant from, Instant boundary, List<RuleCompilationSnapshot.SourceStartProof> proofs,
            Map<String, RuleCompilationSnapshot.LookupSelection> selections) {
        return startCheckpoint(snapshot, compiler, asOfOwner, asOfOwner.user(authorization), from, boundary, proofs, selections);
    }

    static StartCheckpoint startCheckpoint(RuleCompilationSnapshot snapshot, VclCompiler compiler,
            NativeAsOfOwner asOfOwner, NativeAsOfOwner.Transport transport, Instant from, Instant boundary,
            List<RuleCompilationSnapshot.SourceStartProof> proofs,
            Map<String, RuleCompilationSnapshot.LookupSelection> selections) {
        return prepareStart(snapshot, compiler, asOfOwner, transport, from, boundary, proofs, selections).checkpoint();
    }

    record PreparedStart(StartCheckpoint checkpoint, RuleCompilationSnapshot.PreparedLookups lookups,
            com.voltcanary.vcl.VclCompilation compilation) { }

    PreparedStart prepareCompilationStart(RuleCompilationSnapshot snapshot, VclCompiler compiler, String authorization,
            Instant from, Instant boundary, List<RuleCompilationSnapshot.SourceStartProof> proofs,
            Map<String, RuleCompilationSnapshot.LookupSelection> lookups) {
        return prepareStart(snapshot, compiler, asOfOwner, asOfOwner.user(authorization), from, boundary, proofs, lookups);
    }

    static PreparedStart prepareStart(RuleCompilationSnapshot snapshot, VclCompiler compiler,
            NativeAsOfOwner asOfOwner, NativeAsOfOwner.Transport transport, Instant from, Instant boundary,
            List<RuleCompilationSnapshot.SourceStartProof> proofs,
            Map<String, RuleCompilationSnapshot.LookupSelection> selections) {
        var source = snapshot.sourceStartCheckpoint(from, boundary, proofs);
        Map<String, RuleCompilationSnapshot.ReferenceStartPin> references;
        com.voltcanary.vcl.VclCompilation compilation;
        RuleCompilationSnapshot.PreparedLookups prepared = null;
        if (selections.isEmpty()) {
            compilation = snapshot.compile(compiler);
            if (compilation.graph().stream().anyMatch(node -> "lookup_asof".equals(node.method()))) {
                throw new VclException("ASOF_SNAPSHOT_REQUIRED", "");
            }
            references = Map.of();
        } else {
            prepared = snapshot.prepareLookups(compiler, asOfOwner, transport, selections);
            compilation = prepared.compilation();
            references = prepared.referenceStartPins();
        }
        snapshot.verifySourceStartCheckpoint(source);
        var payload = JsonNodeFactory.instance.objectNode().put("profile", "RULE_START/1")
                .put("source", source.digest()).put("compilation", compilation.semanticDigest());
        var pins = payload.putObject("references");
        references.forEach((node, pin) -> pins.putObject(node).put("query", pin.canonicalQuery())
                .put("intervalField", pin.intervalField().toString()).put("manifest", pin.manifestDigest()));
        return new PreparedStart(new StartCheckpoint(source, references, compilation.documentDigest(), compilation.semanticDigest(),
                VclCanonical.sha256(VclCanonical.encode(payload))), prepared, compilation);
    }

    /** Compile against actual retained input cuts within an internal total input-count budget. */
    public RuleCompilationSnapshot captureJournal(String authorization, UUID actor, UUID account, VclDocument document,
            long maxRetainedObservations) {
        if (maxRetainedObservations <= 0) throw new VclException("JOURNAL_INPUT_BUDGET_INVALID", "");
        return capture(authorization, actor, account, document, maxRetainedObservations, Map.of());
    }

    /** Recover a staged window using stored input pins and freshly supplied owner authorization. */
    public DurableJournalWindow restoreWindow(String authorization, UUID actor, UUID account,
            KeyedStateStore store, KeyedStateStore.Key key, String pins, VclCompiler compiler) {
        if (account == null || !account.equals(key.accountId()) || actor == null || authorization == null
                || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) {
            throw new VclException("OWNER_AUTHENTICATION_REQUIRED", "");
        }
        var recipe = DurableJournalWindow.recipe(store.inspect(key, pins).state());
        var document = new VclParser().parse(recipe.document());
        if (document.kind() != VclDocument.Kind.RULE || !key.ruleId().toString().equals(document.spec().path("ruleId").textValue())
                || recipe.membershipBudget() <= 0) throw new VclException("WINDOW_RECIPE_INVALID", "");
        var target = new SourceBindingSnapshots.Target(recipe.binding(), recipe.schemaRevision());
        var captured = capture(authorization, actor, account, document, recipe.membershipBudget(), Map.of(target, recipe.cut()));
        var batch = captured.prepareJournalWindowBatch(compiler, recipe.nodeId(), recipe.membershipBudget());
        var restored = batch.durable(store, key.deploymentEpoch(), recipe.subject(), recipe.window(), recipe.coverageProof());
        if (!key.equals(restored.key()) || !pins.equals(restored.pinsDigest())) throw new VclException("KEY_PINS_CHANGED", "");
        return restored;
    }

    /** Process a bounded account-scoped due batch under fresh caller authority for every restored window. */
    public List<KeyedStateStore.OutputStatus> consumeDueWindows(String authorization, UUID actor, UUID account,
            KeyedStateStore store, VclCompiler compiler, UUID holder, int limit) {
        if (account == null || actor == null || holder == null || store == null || compiler == null
                || authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) {
            throw new VclException("OWNER_AUTHENTICATION_REQUIRED", "");
        }
        var results = new java.util.ArrayList<KeyedStateStore.OutputStatus>();
        for (var candidate : store.dueTimers(account, limit)) {
            if (candidate.scheduled().timer().kind() != KeyedStateStore.TimerKind.WINDOW_CLOSE) continue;
            var restored = restoreWindow(authorization, actor, account, store,
                    candidate.key(), candidate.pinsDigest(), compiler);
            results.add(restored.consumeDiscovered(candidate, holder));
        }
        return List.copyOf(results);
    }

    private RuleCompilationSnapshot capture(String authorization, UUID actor, UUID account, VclDocument document,
            long remainingObservations, Map<SourceBindingSnapshots.Target, SourceCaptureSnapshot.Cut> cuts) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()
                || actor == null || account == null) throw new VclException("OWNER_AUTHENTICATION_REQUIRED", "");
        if (document == null || document.kind() != VclDocument.Kind.RULE) throw new VclException("RULE_REQUIRED", "");
        var spec = document.spec();
        UUID rule = UUID.fromString(spec.path("ruleId").asText());
        UUID grantId = UUID.fromString(spec.path("executionGrantId").asText());
        var bindingTargets = new LinkedHashMap<UUID, SourceBindingSnapshots.Target>();
        var datasetTargets = new LinkedHashMap<UUID, NativeDatasetSnapshots.Target>();
        var policyIds = new java.util.LinkedHashSet<UUID>();
        for (var node : spec.path("nodes")) {
            var config = node.path("config");
            switch (node.path("method").asText()) {
                case "read_stream" -> {
                    UUID id = UUID.fromString(config.path("bindingId").asText());
                    var target = new SourceBindingSnapshots.Target(id, config.path("schemaRevision").longValue());
                    var previous = bindingTargets.putIfAbsent(id, target);
                    if (previous != null && !previous.equals(target)) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
                }
                case "lookup_asof" -> {
                    var reference = config.path("target");
                    UUID id = UUID.fromString(reference.path("datasetId").asText());
                    var target = new NativeDatasetSnapshots.Target(id, reference.path("schemaRevision").longValue());
                    var previous = datasetTargets.putIfAbsent(id, target);
                    if (previous != null && !previous.equals(target)) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
                }
                case "emit_alert" -> policyIds.add(UUID.fromString(config.path("evidencePolicyId").asText()));
                default -> { }
            }
        }
        if (bindingTargets.isEmpty() || bindingTargets.size() > 16 || datasetTargets.size() > 16 || policyIds.size() != 1) {
            throw new VclException("OWNER_TARGET_LIMIT", "");
        }
        if (!cuts.isEmpty() && !cuts.keySet().equals(new java.util.HashSet<>(bindingTargets.values()))) {
            throw new VclException("CAPTURE_CUT_CHANGED", "");
        }
        var grant = grants.capture(authorization, actor, account, rule, grantId);
        boolean requireJournal = remainingObservations > 0;
        var source = requireJournal ? null : bindings.capture(authorization, actor, account, List.copyOf(bindingTargets.values()));
        var nativeData = datasetTargets.isEmpty() ? null : datasets.capture(authorization, List.copyOf(datasetTargets.values()));
        UUID policyId = policyIds.iterator().next();
        var policy = policies.capture(authorization, actor, account, rule, policyId, grant);
        var journals = new LinkedHashMap<SourceBindingSnapshots.Target, SourceCaptureSnapshot>();
        if (requireJournal) {
            for (var target : bindingTargets.values()) {
                var journal = captures.capture(authorization, actor, account, target.id(), target.revision(), cuts.get(target));
                long count = journal.retainedObservationCount();
                if (count > remainingObservations) throw new VclException("JOURNAL_INPUT_BUDGET_EXCEEDED", "");
                remainingObservations -= count;
                journals.put(target, journal);
            }
        }
        return new RuleCompilationSnapshot(document, source, nativeData, grant, policy,
                List.copyOf(bindingTargets.values()), List.copyOf(datasetTargets.values()), journals);
    }
}
