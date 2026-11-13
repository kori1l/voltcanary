package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.vcl.CompilationBundle;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclRuleCutoverIntent;
import com.voltcanary.vcl.VclUnits;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Applies a cutover only under fresh alert-owned confirmation and exact checkpoint pins. */
@Service
public final class ReviewedRuleCutovers {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcCompilationBundleStore bundles;
    private final JdbcRuleDeployments deployments;
    private final JdbcKeyedStateStore keyed;
    private final RuleCompilationSnapshots snapshots;
    private final ConfigurableOwnerReads alert;
    private final VclParser parser = new VclParser();
    private final VclCompiler compiler = new VclCompiler(new VclRegistry(parser), VclUnits.standard());

    public record Lookup(String query, UUID intervalField) { }
    public record Preview(UUID account, UUID bundle, Instant from, Instant boundary,
            List<RuleCompilationSnapshot.SourceStartProof> proofs, Map<String, Lookup> lookups) { }
    public record WindowComparisonRequest(UUID account, UUID bundle, String node, String subjectLiteral,
            Instant from, Instant until, Map<String, Lookup> original, Map<String, Lookup> corrected) { }
    public record ComparedWindow(String inputDigest, String status, String reason, JsonNode literal, List<UUID> evidence,
            Map<String, String> referenceManifestDigests) { }
    public record WindowComparison(String sourceCutDigest, ComparedWindow original, ComparedWindow corrected) { }
    public record HistoricalWindowComparisonRequest(UUID account, UUID epoch, UUID measure, Map<String, Lookup> corrected) { }
    public record HistoricalWindowComparison(String confirmationDigest, WindowComparison comparison) { }
    public record WindowCorrectionRequest(HistoricalWindowComparisonRequest window, String confirmationDigest, boolean updateAlert) { }
    public record WindowCorrection(UUID correctionId, String confirmationDigest, WindowComparison comparison, UUID alertProposalId) { }
    private record PreparedCorrection(JdbcRuleDeployments.Deployment deployment, JdbcKeyedStateStore.HistoricalWindow original,
            WindowComparison comparison, String payload, String digest, IntervalWindowSchedule.Recipe schedule,
            SourceBindingSnapshots.Target source, HistoricalEvaluation corrected, RuleCompilationSnapshot snapshot, JsonNode originalResult,
            RuleCompilationSnapshot.PreparedLookups correctedLookups) { }
    private record HistoricalEvaluation(ComparedWindow value, JsonNode decision, com.voltcanary.vcl.VclSourceCoverage proof,
            boolean aggregate) { }

    public ReviewedRuleCutovers(DataSource source, RuleCompilationSnapshots snapshots, RestClient.Builder builder,
            AnalyticsDownstreamProperties properties) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        keyed = new JdbcKeyedStateStore(source);
        this.snapshots = snapshots;
        var target = properties.getAlertService();
        alert = new ConfigurableOwnerReads(builder, target.getBaseUrl(), target.getTimeout());
    }

    public RuleCompilationSnapshots.StartCheckpoint preview(String authorization, UUID actor, Preview preview) {
        var document = document(bundle(preview.account(), preview.bundle()));
        return snapshots.prepareStartCheckpoint(authorization, actor, preview.account(), document, compiler,
                Long.MAX_VALUE, preview.from(), preview.boundary(), preview.proofs(), selections(preview.lookups()));
    }

    public WindowComparison compareWindows(String authorization, UUID actor, WindowComparisonRequest request) {
        if (request == null || request.account() == null || request.bundle() == null || request.node() == null
                || request.subjectLiteral() == null || request.original() == null || request.original().isEmpty()
                || request.corrected() == null || !request.original().keySet().equals(request.corrected().keySet())
                || request.original().size() > 2) throw new VclException("WINDOW_COMPARISON_INVALID", "");
        var window = new VclWindowPlan.Window(request.from(), request.until());
        var subject = ScalarValue.known(parser, request.subjectLiteral());
        if (subject.isNull()) throw new VclException("WINDOW_SUBJECT_INVALID", "");
        var document = document(bundle(request.account(), request.bundle()));
        var snapshot = snapshots.captureJournal(authorization, actor, request.account(), document, Long.MAX_VALUE);
        var original = snapshots.prepareLookups(snapshot, compiler, authorization, selections(request.original()));
        var originalValue = compareWindow(original, request.node(), subject, window);
        var corrected = snapshots.prepareLookups(snapshot, compiler, authorization, selections(request.corrected()));
        var correctedValue = compareWindow(corrected, request.node(), subject, window);
        snapshot.reauthorize();
        return new WindowComparison(original.sourceCutDigest(), originalValue, correctedValue);
    }

    private ComparedWindow compareWindow(RuleCompilationSnapshot.PreparedLookups lookup, String node, ScalarValue subject,
            VclWindowPlan.Window window) {
        var operator = lookup.compilation().graph().stream().filter(candidate -> node.equals(candidate.id())).findFirst()
                .orElseThrow(() -> new VclException("WINDOW_OPERATOR_REQUIRED", ""));
        var type = operator.fields().get(operator.config().path("subjectFieldId").textValue());
        if (type == null || !type.kind().name().equals(subject.literal().path("type").textValue())) {
            throw new VclException("WINDOW_SUBJECT_INVALID", "");
        }
        long budget = RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES;
        var preview = switch (operator.method()) {
            case "window_aggregate" -> lookup.previewWindowAggregate(node, subject, window, budget);
            case "interval_union_window" -> lookup.previewIntervalUnion(node, VclWindowSessionKernel.identity(subject), window, budget);
            default -> throw new VclException("WINDOW_OPERATOR_REQUIRED", "");
        };
        return comparedWindow(lookup, preview);
    }

    private static ComparedWindow comparedWindow(RuleCompilationSnapshot.PreparedLookups lookup,
            RuleCompilationSnapshot.WindowPreview preview) {
        var manifests = new java.util.TreeMap<String, String>();
        lookup.referenceStartPins().forEach((id, pin) -> manifests.put(id, pin.manifestDigest()));
        var value = preview.localValue();
        return new ComparedWindow(preview.inputDigest(), value.status().name(), value.reason(), value.literal(), value.evidence(), manifests);
    }

    public HistoricalWindowComparison compareHistoricalWindow(String authorization, UUID actor, HistoricalWindowComparisonRequest request) {
        var prepared = prepareHistoricalWindow(authorization, actor, request);
        return new HistoricalWindowComparison(prepared.digest(), prepared.comparison());
    }

    public WindowCorrection publishHistoricalWindow(String authorization, UUID actor, WindowCorrectionRequest request) {
        if (request == null || request.confirmationDigest() == null || !request.confirmationDigest().matches("[0-9a-f]{64}")) {
            throw new VclException("WINDOW_CORRECTION_CONFIRMATION_REQUIRED", "");
        }
        var prepared = prepareHistoricalWindow(authorization, actor, request.window());
        if (!prepared.digest().equals(request.confirmationDigest())) throw new VclException("WINDOW_CORRECTION_CHANGED", "");
        UUID correction = UUID.nameUUIDFromBytes(("VCL_REVIEWED_WINDOW_CORRECTION/1\n" + prepared.digest()).getBytes(StandardCharsets.UTF_8));
        var outputs = request.updateAlert() ? historicalAlertOutputs(prepared, correction) : List.<KeyedStateStore.Output>of();
        var catchup = outputs.isEmpty() ? null : prepared.correctedLookups().correctionCatchup(prepared.schedule().node(),
                prepared.source(), prepared.schedule().window(), prepared.originalResult().path("checkpoint").textValue(),
                prepared.corrected().aggregate());
        prepared.snapshot().reauthorize();
        UUID proposal = keyed.appendWindowCorrection(prepared.deployment(), prepared.original(), correction, prepared.digest(),
                prepared.payload(), outputs, catchup);
        return new WindowCorrection(correction, prepared.digest(), prepared.comparison(), proposal);
    }

    private static List<KeyedStateStore.Output> historicalAlertOutputs(PreparedCorrection prepared, UUID correction) {
        var evaluation = prepared.corrected();
        var decision = evaluation.decision();
        var literal = decision.at("/literal/value");
        if (!"OK".equals(decision.path("status").textValue()) || !literal.isBoolean()) {
            throw new VclException("WINDOW_CORRECTION_EFFECT_UNKNOWN", "");
        }
        boolean matched = literal.booleanValue();
        if (!matched && !prepared.originalResult().path("alertBasis").isTextual()
                && !prepared.originalResult().at("/decision/literal/value").booleanValue()) return List.of();
        var original = prepared.original();
        var schedule = prepared.schedule();
        var value = evaluation.value();
        var proof = evaluation.proof();
        var data = JSON.createObjectNode().put("profile", "vcl-interval-final-measure-1")
                .put("node", schedule.node()).put("finalized", true)
                .put("windowStart", schedule.window().startInclusive().toString())
                .put("windowEnd", schedule.window().endExclusive().toString())
                .put("checkpoint", prepared.originalResult().path("checkpoint").textValue())
                .put("cutDigest", prepared.comparison().sourceCutDigest()).put("inputDigest", value.inputDigest())
                .put("proof", proof.proofId().toString()).put("coverageDigest", proof.eventTimeDigest())
                .put("status", value.status()).put("reason", value.reason())
                .put("supersedes", original.output().id().toString()).put("reviewedCorrection", correction.toString());
        data.set("literal", value.literal());
        data.set("decision", decision);
        data.set("evidence", JSON.valueToTree(value.evidence()));
        data.set("referenceOverride", JSON.valueToTree(prepared.correctedLookups().referenceStartPins()));
        UUID measureId = IntervalWindowSchedule.id(original.pins(), "reviewed-measure:" + correction);
        if (matched) data.put("alertBasis", measureId.toString());
        var measure = new KeyedStateStore.Output(measureId, KeyedStateStore.OutputKind.MEASURE, null,
                KeyedStateStore.State.parse(VclCanonical.encode(data)));
        var intent = prepared.snapshot().sessionAlertIntent(decision.path("nodeId").textValue(), prepared.deployment().bundleDigest())
                .orElseThrow(() -> new VclException("EVALUATOR_PROFILE_UNAVAILABLE", ""));
        String profile = evaluation.aggregate() ? (matched ? SessionAlertProposal.AGGREGATE_CORRECTION : SessionAlertProposal.AGGREGATE_RETRACTION)
                : (matched ? SessionAlertProposal.INTERVAL_CORRECTION : SessionAlertProposal.INTERVAL_RETRACTION);
        var proposal = SessionAlertProposal.create(original.key(), IntervalWindowSchedule.id(original.pins(), "reviewed-alert:" + correction),
                measure, schedule.window(), intent, prepared.source().id(), prepared.source().revision(), proof, proof.eventTimeDigest(), profile);
        return List.of(measure, proposal);
    }

    private PreparedCorrection prepareHistoricalWindow(String authorization, UUID actor, HistoricalWindowComparisonRequest request) {
        if (request == null || request.account() == null || request.epoch() == null || request.measure() == null
                || request.corrected() == null || request.corrected().isEmpty() || request.corrected().size() > 2) {
            throw new VclException("WINDOW_COMPARISON_INVALID", "");
        }
        var deployment = deployments.find(request.account(), request.epoch())
                .filter(value -> value.actor().equals(actor) && value.acceptsExistingWork() && value.start() != null)
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        var bundle = bundle(deployment.account(), deployment.bundle());
        if (!deployment.bundleDigest().equals(bundle.digest())) throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var document = document(bundle);
        var start = snapshots.restoreStartCheckpoint(authorization, actor, deployment.account(), deployments,
                deployment.epoch(), document, compiler, Long.MAX_VALUE);
        if (start.source().sources().size() != 1 || !start.references().keySet().equals(request.corrected().keySet())) {
            throw new VclException("WINDOW_COMPARISON_INVALID", "");
        }
        var historical = keyed.historicalWindow(deployment, request.measure());
        var result = parser.readJson(historical.output().payload().payload().getBytes(StandardCharsets.UTF_8));
        if (!"vcl-interval-final-measure-1".equals(result.path("profile").textValue())
                || !result.path("finalized").isBoolean() || !result.path("finalized").booleanValue()) {
            throw new VclException("WINDOW_MEASURE_UNAVAILABLE", "");
        }
        var schedule = IntervalWindowSchedule.recipe(historical.schedule());
        var window = schedule.window();
        if (!schedule.node().equals(result.path("node").textValue())
                || !window.startInclusive().toString().equals(result.path("windowStart").textValue())
                || !window.endExclusive().toString().equals(result.path("windowEnd").textValue())
                || !IntervalWindowSchedule.occurrence(window).equals(historical.key().occurrenceDigest())
                || schedule.subjectLiteral() == null) throw new VclException("WINDOW_MEASURE_CHANGED", "");
        var source = start.source().sources().getFirst();
        var cut = new SourceCaptureSnapshot.Cut(result.path("checkpoint").textValue(), result.path("cutDigest").textValue());
        var target = new SourceBindingSnapshots.Target(source.binding(), source.schemaRevision());
        var snapshot = snapshots.captureJournalCut(authorization, actor, deployment.account(), document, target, cut);
        var originalPins = result.has("referenceOverride")
                ? RuleStartCheckpointRecipe.referencePins(result.path("referenceOverride")) : start.references();
        if (!originalPins.keySet().equals(start.references().keySet())) throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
        var original = snapshots.prepareLookups(snapshot, compiler, authorization, RuleStartCheckpointRecipe.selections(originalPins));
        if (!original.referenceStartPins().equals(originalPins)) throw new VclException("INTERVAL_REFERENCE_CHANGED", "");
        UUID proof = UUID.fromString(result.path("proof").textValue());
        String proofDigest = result.path("coverageDigest").textValue();
        var originalValue = evaluateHistoricalWindow(original, historical.key(), schedule, cut, proof, proofDigest).value();
        if (!originalValue.inputDigest().equals(result.path("inputDigest").textValue())
                || !originalValue.status().equals(result.path("status").textValue())
                || !java.util.Objects.equals(originalValue.reason(), result.path("reason").textValue())
                || !java.util.Objects.equals(originalValue.literal(), result.path("literal").isNull() ? null : result.path("literal"))) {
            throw new VclException("WINDOW_MEASURE_CHANGED", "");
        }
        var corrected = snapshots.prepareLookups(snapshot, compiler, authorization, selections(request.corrected()));
        var correctedValue = evaluateHistoricalWindow(corrected, historical.key(), schedule, cut, proof, proofDigest);
        snapshot.reauthorize();
        if (deployments.find(deployment.account(), deployment.epoch()).filter(deployment::equals).isEmpty()) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        var comparison = new WindowComparison(cut.digest(), originalValue, correctedValue.value());
        var audit = JSON.createObjectNode().put("profile", "VCL_REVIEWED_WINDOW_CORRECTION/1")
                .put("actor", actor.toString()).put("account", deployment.account().toString())
                .put("rule", deployment.rule().toString()).put("epoch", deployment.epoch().toString())
                .put("deploymentRevision", Long.toString(deployment.revision())).put("bundleDigest", deployment.bundleDigest())
                .put("keyDigest", historical.key().digest()).put("originalMeasure", request.measure().toString())
                .put("originalMeasureDigest", historical.output().digest()).put("checkpoint", cut.head())
                .put("proof", proof.toString()).put("proofDigest", proofDigest);
        audit.set("comparison", JSON.valueToTree(comparison));
        audit.set("correctedReferences", JSON.valueToTree(corrected.referenceStartPins()));
        String payload = VclCanonical.encode(audit);
        return new PreparedCorrection(deployment, historical, comparison, payload, VclCanonical.sha256(payload),
                schedule, target, correctedValue, snapshot, result, corrected);
    }

    private HistoricalEvaluation evaluateHistoricalWindow(RuleCompilationSnapshot.PreparedLookups lookup, KeyedStateStore.Key key,
            IntervalWindowSchedule.Recipe schedule, SourceCaptureSnapshot.Cut cut, UUID proof, String proofDigest) {
        long checkpoint = com.voltcanary.vcl.VclObservationReadiness.checkpoint(cut.head());
        var operator = lookup.compilation().graph().stream().filter(node -> schedule.node().equals(node.id())).findFirst()
                .orElseThrow(() -> new VclException("WINDOW_OPERATOR_REQUIRED", ""));
        var subject = ScalarValue.known(parser, schedule.subjectLiteral());
        if ("window_aggregate".equals(operator.method())) {
            var batch = lookup.journalAggregate(schedule.node());
            if (!batch.aggregateSubjectDigest(subject).equals(key.subjectDigest())) throw new VclException("WINDOW_SUBJECT_INVALID", "");
            var evaluated = batch.evaluateCovered(subject, schedule.window(), schedule.window().endExclusive(), checkpoint, (from, until) -> proof)
                    .orElseThrow(() -> new VclException("WINDOW_FRONTIER_PENDING", ""));
            if (!cut.equals(evaluated.cut())) throw new VclException("WINDOW_INPUT_CHECKPOINT_MISMATCH", "");
            if (!evaluated.proof().eventTimeDigest().equals(proofDigest)) throw new VclException("WINDOW_COVERAGE_CHANGED", "");
            var result = new RuleCompilationSnapshot.WindowPreview(evaluated.inputDigest(), evaluated.value());
            return new HistoricalEvaluation(comparedWindow(lookup, result), evaluated.decision(), evaluated.proof(), true);
        } else if ("interval_union_window".equals(operator.method())) {
            if (!VclWindowSessionKernel.identity(subject).equals(key.subjectDigest())) throw new VclException("WINDOW_SUBJECT_INVALID", "");
            var evaluated = lookup.finalizeIntervalUnion(schedule.node(), key.subjectDigest(), schedule.window(),
                    lookup.referenceStartPins(), checkpoint, (from, until) -> proof)
                    .orElseThrow(() -> new VclException("WINDOW_FRONTIER_PENDING", ""));
            if (!cut.digest().equals(evaluated.cutDigest()) || !cut.head().equals(evaluated.checkpoint())) {
                throw new VclException("WINDOW_INPUT_CHECKPOINT_MISMATCH", "");
            }
            if (!evaluated.proof().eventTimeDigest().equals(proofDigest)) throw new VclException("WINDOW_COVERAGE_CHANGED", "");
            var decision = lookup.intervalDecision(schedule.node(), subject).evaluate(evaluated.result().localValue());
            return new HistoricalEvaluation(comparedWindow(lookup, evaluated.result()), decision, evaluated.proof(), false);
        } else throw new VclException("WINDOW_OPERATOR_REQUIRED", "");
    }

    private Map<String, RuleCompilationSnapshot.LookupSelection> selections(Map<String, Lookup> lookups) {
        var selections = new LinkedHashMap<String, RuleCompilationSnapshot.LookupSelection>();
        if (lookups != null) lookups.forEach((node, lookup) -> {
            if (lookup == null || lookup.query() == null || lookup.intervalField() == null) {
                throw new VclException("ASOF_PROJECTION_CONFLICT", "");
            }
            selections.put(node, new RuleCompilationSnapshot.LookupSelection(parser.parse(lookup.query()), lookup.intervalField()));
        });
        return selections;
    }

    public JdbcRuleDeployments.Deployment apply(String authorization, UUID actor, UUID reviewId,
            RuleCompilationSnapshots.StartCheckpoint checkpoint) {
        var review = review(authorization, actor, reviewId);
        var intent = review.intent();
        if (checkpoint == null || !intent.checkpointDigest().equals(checkpoint.digest())) {
            throw new VclException("START_CHECKPOINT_CHANGED", "");
        }
        var bundle = bundle(review.account(), intent.bundleId());
        var root = parser.readJson(bundle.payload().getBytes(StandardCharsets.UTF_8));
        var compiled = root.path("compilation");
        var document = parser.parse(compiled.path("canonicalDocument").textValue());
        var grantPins = root.at("/ownerValidation/execution/resourceRevisions");
        boolean grantPinned = false;
        for (var pin : grantPins) {
            if ("EXECUTION_GRANT".equals(pin.path("kind").textValue())
                    && intent.grantId().toString().equals(pin.path("id").textValue())
                    && review.grantEpoch() == pin.path("revision").longValue()) grantPinned = true;
        }
        if (!bundle.digest().equals(intent.bundleDigest())
                || !grantPinned
                || !intent.ruleId().toString().equals(document.spec().path("ruleId").textValue())
                || !intent.grantId().toString().equals(document.spec().path("executionGrantId").textValue())) {
            throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        }
        snapshots.verifyStartCheckpoint(authorization, actor, review.account(), document, compiler,
                Long.MAX_VALUE, checkpoint);
        var staged = deployments.stage(actor, bundle, intent.successorEpoch());
        Runnable renew = () -> {
            if (!review.equals(review(authorization, actor, reviewId))) throw new VclException("REVIEW_CONFLICT", "");
        };
        try {
            return snapshots.cutover(authorization, actor, review.account(), deployments, intent.predecessorEpoch(),
                    intent.successorEpoch(), document, compiler, Long.MAX_VALUE,
                    checkpoint, renew, staged.state() != JdbcRuleDeployments.State.STAGED);
        } catch (VclException pending) {
            if (!"EVALUATOR_READINESS_UNAVAILABLE".equals(pending.code())) throw pending;
            return deployments.find(review.account(), intent.successorEpoch()).orElseThrow(() -> pending);
        }
    }

    private record Review(UUID account, long grantEpoch, VclRuleCutoverIntent intent, String digest) { }

    public JdbcRuleDeployments.Deployment pause(String authorization, UUID actor, UUID reviewId) {
        var review = pauseReview(authorization, actor, reviewId);
        var intent = review.intent();
        return deployments.pause(review.account(), intent.epoch(), current -> {
            long reviewedRevision = current.state() == JdbcRuleDeployments.State.PAUSED
                    ? current.revision() - 1 : current.revision();
            if (!current.actor().equals(actor) || !current.rule().equals(intent.ruleId())
                    || !current.grant().equals(intent.grantId()) || !current.bundleDigest().equals(intent.bundleDigest())
                    || reviewedRevision != intent.revision() || !review.equals(pauseReview(authorization, actor, reviewId))) {
                throw new VclException("REVIEW_CONFLICT", "");
            }
        });
    }

    private record PauseReview(UUID account, com.voltcanary.vcl.VclRulePauseIntent intent, String digest) { }

    private PauseReview pauseReview(String authorization, UUID actor, UUID id) {
        try {
            var json = parser.readJson(alert.confirmedRulePause(authorization, id,
                    System.nanoTime() + Duration.ofSeconds(10).toNanos()));
            UUID account = UUID.fromString(json.path("accountId").textValue());
            var value = json.path("intent");
            var intent = new com.voltcanary.vcl.VclRulePauseIntent(UUID.fromString(value.path("ruleId").textValue()),
                    UUID.fromString(value.path("grantId").textValue()), UUID.fromString(value.path("epoch").textValue()),
                    value.path("revision").longValue(), value.path("bundleDigest").textValue());
            String digest = json.path("reviewDigest").textValue();
            if (!id.toString().equals(json.path("id").textValue()) || !actor.toString().equals(json.path("actorId").textValue())
                    || json.path("confirmedAt").isNull() || json.path("confirmedAt").isMissingNode()
                    || !intent.reviewDigest(account, actor, id).equals(digest)) {
                throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            }
            return new PauseReview(account, intent, digest);
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }

    private Review review(String authorization, UUID actor, UUID id) {
        try {
            var json = parser.readJson(alert.confirmedRuleCutover(authorization, id,
                    System.nanoTime() + Duration.ofSeconds(10).toNanos()));
            UUID account = UUID.fromString(json.path("accountId").textValue());
            long grantEpoch = json.path("grantEpoch").longValue();
            var value = json.path("intent");
            var predecessor = value.path("predecessorEpoch");
            var intent = new VclRuleCutoverIntent(UUID.fromString(value.path("ruleId").textValue()),
                    UUID.fromString(value.path("grantId").textValue()),
                    predecessor.isNull() || predecessor.isMissingNode() ? null : UUID.fromString(predecessor.textValue()),
                    UUID.fromString(value.path("successorEpoch").textValue()), UUID.fromString(value.path("bundleId").textValue()),
                    value.path("bundleDigest").textValue(), value.path("checkpointDigest").textValue());
            String digest = json.path("reviewDigest").textValue();
            if (!id.toString().equals(json.path("id").textValue()) || !actor.toString().equals(json.path("actorId").textValue())
                    || grantEpoch < 1 || json.path("confirmedAt").isNull() || json.path("confirmedAt").isMissingNode()
                    || !intent.reviewDigest(account, actor, id, grantEpoch).equals(digest)) {
                throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            }
            return new Review(account, grantEpoch, intent, digest);
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }

    private CompilationBundle bundle(UUID account, UUID id) {
        return bundles.find(account, id).orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
    }

    private com.fasterxml.jackson.databind.JsonNode compilation(CompilationBundle bundle) {
        return parser.readJson(bundle.payload().getBytes(StandardCharsets.UTF_8)).path("compilation");
    }

    private VclDocument document(CompilationBundle bundle) {
        return parser.parse(compilation(bundle).path("canonicalDocument").textValue());
    }
}
