package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclTimingPlan;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclSourceCoverage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongFunction;

/** One durable, historical window evaluation over a prepared rule and its retained input cut. */
public final class DurableJournalWindow {
    private static final VclParser PARSER = new VclParser();
    static final String PROFILE = "vcl-durable-journal-window-3";
    private final KeyedStateStore store;
    private final KeyedStateStore.Key key;
    private final String pins;
    private final UUID batchId;
    private final UUID timerId;
    private final UUID outputId;
    private final UUID coverageProof;
    private final Instant due;
    private final VclTimingPlan timing;
    private final String timingScope;
    private final String progressScope;
    private final LongFunction<RuleCompilationSnapshot.WindowPreview> preview;
    private final JournalWindowDecision decision;
    private final Function<UUID, VclSourceCoverage> coverage;
    private final java.util.function.Supplier<UUID> discoverProof;
    private final Function<VclSourceCoverage, FinalInput> finalInput;
    private final Runnable authorize;
    private final Recipe recipe;
    private final Commit commit;
    private final boolean requireFinalization;
    private final AlertIntent alertIntent;

    @FunctionalInterface
    interface Commit {
        KeyedStateStore.Receipt apply(KeyedStateStore.Key key, KeyedStateStore.Lease lease,
                KeyedStateStore.Transition transition, JdbcJournalWatermarks.Candidate watermark);
    }

    record FinalInput(SourceCaptureSnapshot.Cut cut, String inputDigest,
            com.voltcanary.analytics.vcl.ScalarValue value) { }

    record AlertIntent(String bundleDigest, UUID grant, long grantEpoch, String grantDigest,
            UUID policy, String policyDigest, String severity) { }

    record Recipe(String document, String nodeId, UUID binding, long schemaRevision, SourceCaptureSnapshot.Cut cut,
            long membershipBudget, String subject, VclWindowPlan.Window window, UUID coverageProof) {
        ObjectNode json() {
            return JsonNodeFactory.instance.objectNode().put("document", document).put("nodeId", nodeId)
                    .put("binding", binding.toString()).put("schemaRevision", Long.toString(schemaRevision))
                    .put("head", cut.head()).put("cutDigest", cut.digest()).put("membershipBudget", Long.toString(membershipBudget))
                    .put("subject", subject).put("start", window.startInclusive().toString())
                    .put("end", window.endExclusive().toString()).put("coverageProof", coverageProof == null ? null : coverageProof.toString());
        }
    }

    DurableJournalWindow(KeyedStateStore store, UUID account, UUID rule, UUID executionEpoch, String subject,
            VclWindowPlan.Window window, UUID coverageProof, String cut, String plan, VclTimingPlan timing, String timingScope,
            String progressScope, String graph, Instant due,
            LongFunction<RuleCompilationSnapshot.WindowPreview> preview, JournalWindowDecision decision,
            java.util.function.Supplier<UUID> discoverProof, Function<UUID, VclSourceCoverage> coverage,
            Function<VclSourceCoverage, FinalInput> finalInput,
            Runnable authorize, Recipe recipe, Commit commit, AlertIntent alertIntent) {
        this.store = store;
        this.coverageProof = coverageProof;
        this.due = due;
        this.timing = timing;
        this.timingScope = timingScope;
        this.progressScope = progressScope;
        this.preview = preview;
        this.decision = decision;
        this.coverage = coverage;
        this.discoverProof = discoverProof;
        this.finalInput = finalInput;
        this.authorize = authorize;
        this.recipe = recipe;
        this.commit = commit;
        this.alertIntent = alertIntent;
        this.requireFinalization = alertIntent != null;
        // An automatically opened window keeps the same identity as the input head grows.
        String identityScope = alertIntent != null && coverageProof == null ? progressScope : graph + "\n" + plan;
        key = new KeyedStateStore.Key(account, rule, executionEpoch, VclCanonical.sha256(subject),
                VclCanonical.sha256(PROFILE + "\n" + identityScope + "\n" + window.startInclusive() + "\n" + window.endExclusive()));
        pins = VclCanonical.sha256(PROFILE + "\n" + key.digest() + "\n" + cut + "\n" + timing.digest() + "\n" + graph + "\n" + coverageProof);
        batchId = identity("batch");
        timerId = identity("timer");
        outputId = identity("measure");
    }

    public KeyedStateStore.Key key() { return key; }
    public String pinsDigest() { return pins; }

    public KeyedStateStore.Receipt stage(UUID holder, long byteBudget) {
        var evaluated = preview.apply(byteBudget);
        var value = evaluated.localValue();
        var payload = JsonNodeFactory.instance.objectNode().put("profile", PROFILE).put("inputDigest", evaluated.inputDigest())
                .put("phase", "STAGED").put("status", value.status().name()).put("reason", value.reason());
        payload.set("literal", value.literal());
        payload.set("decision", decision.evaluate(value));
        payload.set("recipe", recipe.json());
        var evidence = payload.putArray("evidence");
        value.evidence().forEach(id -> evidence.add(id.toString()));
        State next = state(payload);
        authorize.run();
        store.register(key, pins, State.parse("{\"phase\":\"EMPTY\"}"));
        var receipt = store.inputReceipt(key, pins, KeyedStateStore.InputKind.OBSERVATION, batchId, 1);
        if (receipt.isPresent()) {
            if (!next.digest().equals(receipt.get().inputDigest())) throw new VclException("KEY_INPUT_CONFLICT", "");
            return new KeyedStateStore.Receipt(receipt.get().committedRevision(), true);
        }
        var lease = store.acquire(key, pins, holder);
        authorize.run();
        var timer = new KeyedStateStore.Timer(timerId, KeyedStateStore.TimerKind.WINDOW_CLOSE, due, next);
        return commit.apply(key, lease, new KeyedStateStore.Transition(batchId, 1, next.digest(), 0, next, List.of(timer), null), null);
    }

    public KeyedStateStore.OutputStatus consumeDue(UUID holder) {
        return consumeDue(holder, null);
    }

    private KeyedStateStore.OutputStatus consumeDue(UUID holder, KeyedStateStore.ScheduledTimer discovered) {
        authorize.run();
        var receipt = store.inputReceipt(key, pins, KeyedStateStore.InputKind.TIMER, timerId, 1);
        if (receipt.isPresent()) return authorizedOutput();
        var lease = store.acquire(key, pins, holder);
        if (store.inputReceipt(key, pins, KeyedStateStore.InputKind.TIMER, timerId, 1).isPresent()) {
            return authorizedOutput();
        }
        var scheduled = lease.timers().stream().filter(timer -> timer.timer().id().equals(timerId)).findFirst()
                .orElseThrow(() -> new VclException("WINDOW_TIMER_UNAVAILABLE", ""));
        if (discovered != null && !discovered.equals(scheduled)) {
            throw new VclException("WINDOW_TIMER_STATE_CHANGED", "");
        }
        var staged = lease.snapshot().state();
        if (!staged.equals(scheduled.timer().payload())) throw new VclException("WINDOW_TIMER_STATE_CHANGED", "");
        var result = (ObjectNode) PARSER.readJson(staged.payload().getBytes(StandardCharsets.UTF_8));
        UUID selectedProof = coverageProof == null && requireFinalization ? discoverProof.get() : coverageProof;
        var proof = selectedProof == null ? null : coverage.apply(selectedProof);
        JdbcJournalWatermarks.Candidate candidate = null;
        result.put("phase", "MEASURED").put("coverage", proof == null ? "UNKNOWN" : proof.coverage())
                .put("coverageReason", proof == null ? "PROOF_UNAVAILABLE" : proof.reason()).put("finalized", false);
        if (selectedProof != null) result.put("coverageProof", selectedProof.toString());
        if (proof != null && "COVERED".equals(proof.coverage())) {
            var evidence = proof.evidence();
            var pin = result.putObject("coveragePin")
                    .put("binding", recipe.binding().toString()).put("schemaRevision", Long.toString(recipe.schemaRevision()))
                    .put("cutDigest", recipe.cut().digest()).put("snapshot", proof.snapshotId().toString())
                    .put("generation", proof.inputGeneration().toString()).put("proof", proof.proofId().toString())
                    .put("requiredCheckpoint", proof.requiredCheckpoint())
                    .put("producerEpoch", evidence.position().epoch().toString()).put("sequence", evidence.position().sequence())
                    .put("from", evidence.from().toString()).put("until", evidence.until().toString());
            if (proof.acceptedAt() != null) pin.put("acceptedAt", proof.acceptedAt().toString());
            if (evidence.eventTimeBasis() != null) pin.put("eventTimeBasis", evidence.eventTimeBasis());
            if (!evidence.parts().isEmpty()) {
                var parts = pin.putArray("coverageParts");
                evidence.parts().forEach(id -> parts.add(id.toString()));
            }
            pin.put("digest", VclCanonical.sha256(VclCanonical.encode(pin)));
            var progress = timing.requiredInputs().stream().map(input -> {
                boolean coveredInput = input.kind() == com.voltcanary.vcl.VclMetadata.Resource.BINDING
                        && input.id().equals(recipe.binding()) && input.revision() == recipe.schemaRevision()
                        && proof.acceptedAt() != null;
                return new VclTimingPlan.Progress(input, coveredInput ? evidence.until() : null,
                        coveredInput ? proof.acceptedAt() : null);
            }).toList();
            var frontier = timing.watermark(progress, timing.digest(), timingScope);
            var watermark = result.putObject("watermark").put("status", frontier.status().name())
                    .put("reason", frontier.reason());
            watermark.set("literal", frontier.literal());
            if (frontier.status() == com.voltcanary.analytics.vcl.ScalarValue.Status.OK) {
                String scope = VclCanonical.sha256(String.join("\n", "RULE_JOURNAL_WATERMARK/1", key.ruleId().toString(),
                        progressScope, recipe.binding().toString(), Long.toString(recipe.schemaRevision()),
                        proof.snapshotId().toString(), proof.inputGeneration().toString(), evidence.position().epoch().toString(),
                        proof.qualifiesEventTime() ? VclSourceCoverage.SOURCE_EVENT_TIME : "UNQUALIFIED"));
                candidate = new JdbcJournalWatermarks.Candidate(scope, evidence.from(), evidence.until(),
                        Instant.parse(frontier.literal().path("value").textValue()), pin.path("digest").textValue(),
                        requireFinalization ? recipe.window() : null);
            }
        }
        if (requireFinalization) {
            if (candidate == null || candidate.from().isAfter(recipe.window().startInclusive())
                    || candidate.watermark().isBefore(recipe.window().endExclusive())) {
                throw new VclException("WINDOW_FRONTIER_PENDING", "");
            }
            if (!proof.qualifiesEventTime()) throw new VclException("WINDOW_EVENT_TIME_UNQUALIFIED", "");
            var evaluated = finalInput.apply(proof);
            var value = evaluated.value();
            result.put("finalized", true).put("inputDigest", evaluated.inputDigest())
                    .put("status", value.status().name()).put("reason", value.reason());
            result.set("literal", value.literal());
            result.set("decision", decision.evaluate(value));
            var evidence = result.putArray("evidence");
            value.evidence().forEach(id -> evidence.add(id.toString()));
            var pin = (ObjectNode) result.path("coveragePin");
            pin.remove("digest");
            pin.put("cutDigest", evaluated.cut().digest()).put("headCheckpoint", evaluated.cut().head())
                    .put("eventTimeDigest", proof.eventTimeDigest());
            String evidenceDigest = VclCanonical.sha256(VclCanonical.encode(pin));
            pin.put("digest", evidenceDigest);
            candidate = new JdbcJournalWatermarks.Candidate(candidate.scopeDigest(), candidate.from(), candidate.until(),
                    candidate.watermark(), evidenceDigest, candidate.finalizing());
        }
        State next = state(result);
        result.remove("recipe");
        State output = state(result);
        var outputs = new java.util.ArrayList<KeyedStateStore.Output>();
        outputs.add(new KeyedStateStore.Output(outputId, KeyedStateStore.OutputKind.MEASURE, null, output));
        var verdict = result.at("/decision/literal");
        if (alertIntent != null && "OK".equals(result.at("/decision/status").textValue())
                && "BOOLEAN".equals(verdict.path("type").textValue()) && verdict.path("value").isBoolean()
                && verdict.path("value").booleanValue()) {
            outputs.add(alertProposal(output, result.path("coveragePin")));
        }
        authorize.run();
        if (proof != null && "COVERED".equals(proof.coverage()) && !proof.equals(coverage.apply(selectedProof))) {
            throw new VclException("WINDOW_COVERAGE_CHANGED", "");
        }
        commit.apply(key, lease, new KeyedStateStore.Transition(timerId, scheduled.stateRevision(), scheduled.timer().digest(),
                lease.snapshot().revision(), next, List.of(), new KeyedStateStore.TimerInput(timerId, scheduled.stateRevision()),
                outputs), candidate);
        return authorizedOutput();
    }

    private KeyedStateStore.Output alertProposal(State measure, com.fasterxml.jackson.databind.JsonNode coveragePin) {
        var window = recipe.window();
        String occurrence = VclCanonical.sha256(String.join("\n", "RULE_WINDOW_OCCURRENCE/1",
                window.startInclusive().toString(), window.endExclusive().toString()));
        String dedup = VclCanonical.sha256(String.join("\n", "RULE_SUBJECT_OCCURRENCE/1", key.ruleId().toString(),
                key.subjectDigest(), occurrence));
        var payload = JsonNodeFactory.instance.objectNode().put("profile", "VCL_WINDOW_ALERT_PROPOSAL/2")
                .put("account", key.accountId().toString()).put("rule", key.ruleId().toString())
                .put("epoch", key.deploymentEpoch().toString()).put("bundleDigest", alertIntent.bundleDigest())
                .put("grant", alertIntent.grant().toString()).put("grantEpoch", Long.toString(alertIntent.grantEpoch()))
                .put("grantDigest", alertIntent.grantDigest()).put("policy", alertIntent.policy().toString())
                .put("policyDigest", alertIntent.policyDigest()).put("severity", alertIntent.severity())
                .put("dedup", dedup).put("subjectDigest", key.subjectDigest()).put("occurrenceDigest", occurrence)
                .put("windowStart", window.startInclusive().toString()).put("windowEnd", window.endExclusive().toString())
                .put("binding", recipe.binding().toString()).put("schemaRevision", Long.toString(recipe.schemaRevision()))
                .put("snapshot", coveragePin.path("snapshot").textValue()).put("generation", coveragePin.path("generation").textValue())
                .put("measure", outputId.toString()).put("measureDigest", measure.digest())
                .put("coverageProof", coveragePin.path("proof").textValue()).put("coverageDigest", coveragePin.path("digest").textValue())
                .put("coverageProofDigest", coveragePin.path("eventTimeDigest").textValue());
        return new KeyedStateStore.Output(identity("alert-proposal"), KeyedStateStore.OutputKind.ALERT_PROPOSAL, null, state(payload));
    }

    /** A discovered timer is only a hint; the store still checks due time and lease on commit. */
    KeyedStateStore.OutputStatus consumeDiscovered(KeyedStateStore.DueTimer discovered, UUID holder) {
        if (!key.equals(discovered.key()) || !pins.equals(discovered.pinsDigest())
                || discovered.scheduled().timer().kind() != KeyedStateStore.TimerKind.WINDOW_CLOSE
                || !timerId.equals(discovered.scheduled().timer().id())
                || discovered.scheduled().stateRevision() != 1) {
            throw new VclException("WINDOW_TIMER_STATE_CHANGED", "");
        }
        return consumeDue(holder, discovered.scheduled());
    }

    static Recipe recipe(State state) {
        try {
            var root = PARSER.readJson(state.payload().getBytes(StandardCharsets.UTF_8));
            var value = root.path("recipe");
            if (!PROFILE.equals(root.path("profile").textValue()) || !value.isObject() || value.size() != 11) {
                throw new IllegalArgumentException();
            }
            String proof = value.path("coverageProof").textValue();
            return new Recipe(value.path("document").textValue(), value.path("nodeId").textValue(),
                    UUID.fromString(value.path("binding").textValue()), Long.parseLong(value.path("schemaRevision").textValue()),
                    new SourceCaptureSnapshot.Cut(value.path("head").textValue(), value.path("cutDigest").textValue()),
                    Long.parseLong(value.path("membershipBudget").textValue()), value.path("subject").textValue(),
                    new VclWindowPlan.Window(Instant.parse(value.path("start").textValue()), Instant.parse(value.path("end").textValue())),
                    proof == null ? null : UUID.fromString(proof));
        } catch (RuntimeException invalid) {
            throw new VclException("WINDOW_RECIPE_INVALID", "");
        }
    }

    private KeyedStateStore.OutputStatus authorizedOutput() {
        var output = store.inspectOutput(key, pins, outputId);
        authorize.run();
        return output;
    }

    private UUID identity(String purpose) {
        return UUID.nameUUIDFromBytes((pins + "\n" + purpose).getBytes(StandardCharsets.UTF_8));
    }

    private static State state(ObjectNode json) {
        return State.parse(VclCanonical.encode(json));
    }
}
