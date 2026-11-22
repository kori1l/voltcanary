package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclWindowSessionKernel;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.ComparedWindow;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.HistoricalWindowComparison;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.HistoricalWindowComparisonRequest;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.WindowComparison;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.WindowCorrection;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.WindowCorrectionRequest;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.WindowPreview;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Reconstructs, confirms and atomically publishes historical corrections. */
final class ReviewedWindowCorrections {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcRuleDeployments deployments;
    private final JdbcKeyedStateStore keyed;
    private final RuleCompilationSnapshots snapshots;
    private final ConfigurableOwnerReads alert;
    private final VclParser parser;
    private final VclCompiler compiler;
    private final ReviewedRuleCompilation compilationReads;

    ReviewedWindowCorrections(
        JdbcRuleDeployments deployments,
        JdbcKeyedStateStore keyed,
        RuleCompilationSnapshots snapshots,
        ConfigurableOwnerReads alert,
        VclParser parser,
        VclCompiler compiler,
        ReviewedRuleCompilation compilationReads
    ) {
        this.deployments = deployments;
        this.keyed = keyed;
        this.snapshots = snapshots;
        this.alert = alert;
        this.parser = parser;
        this.compiler = compiler;
        this.compilationReads = compilationReads;
    }

    record PreparedCorrection(
        JdbcRuleDeployments.Deployment deployment,
        JdbcKeyedStateStore.HistoricalWindow original,
        WindowComparison comparison,
        String payload,
        String digest,
        IntervalWindowSchedule.Recipe schedule,
        SourceBindingSnapshots.Target source,
        HistoricalEvaluation corrected,
        RuleCompilationSnapshot snapshot,
        JsonNode originalResult,
        RulePreparedLookups correctedLookups
    ) {}

    record HistoricalEvaluation(
        ComparedWindow value,
        JsonNode decision,
        com.voltcanary.vcl.VclSourceCoverage proof,
        boolean aggregate
    ) {}

    HistoricalWindowComparison compareHistoricalWindow(
        String authorization,
        UUID actor,
        HistoricalWindowComparisonRequest request
    ) {
        var prepared = prepareHistoricalWindow(authorization, actor, request);
        return new HistoricalWindowComparison(prepared.digest(), prepared.comparison());
    }

    WindowCorrection publishHistoricalWindow(
        String authorization,
        UUID actor,
        WindowCorrectionRequest request
    ) {
        if (
            request == null ||
            request.confirmationDigest() == null ||
            !request.confirmationDigest().matches("[0-9a-f]{64}")
        ) {
            throw new VclException("WINDOW_CORRECTION_CONFIRMATION_REQUIRED", "");
        }
        var prepared = prepareHistoricalWindow(authorization, actor, request.window());
        if (!prepared.digest().equals(request.confirmationDigest())) throw new VclException(
            "WINDOW_CORRECTION_CHANGED",
            ""
        );
        UUID correction = UUID.nameUUIDFromBytes(
            ("VCL_REVIEWED_WINDOW_CORRECTION/1\n" + prepared.digest()).getBytes(
                StandardCharsets.UTF_8
            )
        );
        var outputs = request.updateAlert()
            ? historicalAlertOutputs(prepared, correction)
            : List.<KeyedStateStore.Output>of();
        var catchup = outputs.isEmpty()
            ? null
            : prepared
                  .correctedLookups()
                  .correctionCatchup(
                      prepared.schedule().node(),
                      prepared.source(),
                      prepared.schedule().window(),
                      prepared.originalResult().path("checkpoint").textValue(),
                      prepared.corrected().aggregate()
                  );
        prepared.snapshot().reauthorize();
        UUID proposal = keyed.appendWindowCorrection(
            prepared.deployment(),
            prepared.original(),
            correction,
            prepared.digest(),
            prepared.payload(),
            outputs,
            catchup
        );
        return new WindowCorrection(correction, prepared.digest(), prepared.comparison(), proposal);
    }

    static List<KeyedStateStore.Output> historicalAlertOutputs(
        PreparedCorrection prepared,
        UUID correction
    ) {
        var evaluation = prepared.corrected();
        var decision = evaluation.decision();
        var literal = decision.at("/literal/value");
        if (!"OK".equals(decision.path("status").textValue()) || !literal.isBoolean()) {
            throw new VclException("WINDOW_CORRECTION_EFFECT_UNKNOWN", "");
        }
        boolean matched = literal.booleanValue();
        if (
            !matched &&
            !prepared.originalResult().path("alertBasis").isTextual() &&
            !prepared.originalResult().at("/decision/literal/value").booleanValue()
        ) return List.of();
        var original = prepared.original();
        var schedule = prepared.schedule();
        var value = evaluation.value();
        var proof = evaluation.proof();
        var data = JSON.createObjectNode()
            .put("profile", "vcl-interval-final-measure-1")
            .put("node", schedule.node())
            .put("finalized", true)
            .put("windowStart", schedule.window().startInclusive().toString())
            .put("windowEnd", schedule.window().endExclusive().toString())
            .put("checkpoint", prepared.originalResult().path("checkpoint").textValue())
            .put("cutDigest", prepared.comparison().sourceCutDigest())
            .put("inputDigest", value.inputDigest())
            .put("proof", proof.proofId().toString())
            .put("coverageDigest", proof.eventTimeDigest())
            .put("status", value.status())
            .put("reason", value.reason())
            .put("supersedes", original.output().id().toString())
            .put("reviewedCorrection", correction.toString());
        data.set("literal", value.literal());
        data.set("decision", decision);
        data.set("evidence", JSON.valueToTree(value.evidence()));
        data.set(
            "referenceOverride",
            JSON.valueToTree(prepared.correctedLookups().referenceStartPins())
        );
        UUID measureId = IntervalWindowSchedule.id(
            original.pins(),
            "reviewed-measure:" + correction
        );
        if (matched) data.put("alertBasis", measureId.toString());
        var measure = new KeyedStateStore.Output(
            measureId,
            KeyedStateStore.OutputKind.MEASURE,
            null,
            KeyedStateStore.State.parse(VclCanonical.encode(data))
        );
        var intent = prepared
            .snapshot()
            .sessionAlertIntent(
                decision.path("nodeId").textValue(),
                prepared.deployment().bundleDigest()
            )
            .orElseThrow(() -> new VclException("EVALUATOR_PROFILE_UNAVAILABLE", ""));
        String profile = evaluation.aggregate()
            ? matched
                ? SessionAlertProposal.AGGREGATE_CORRECTION
                : SessionAlertProposal.AGGREGATE_RETRACTION
            : matched
              ? SessionAlertProposal.INTERVAL_CORRECTION
              : SessionAlertProposal.INTERVAL_RETRACTION;
        var proposal = SessionAlertProposal.create(
            original.key(),
            IntervalWindowSchedule.id(original.pins(), "reviewed-alert:" + correction),
            measure,
            schedule.window(),
            intent,
            prepared.source().id(),
            prepared.source().revision(),
            proof,
            proof.eventTimeDigest(),
            profile
        );
        return List.of(measure, proposal);
    }

    PreparedCorrection prepareHistoricalWindow(
        String authorization,
        UUID actor,
        HistoricalWindowComparisonRequest request
    ) {
        try {
            return reconstructHistoricalWindow(authorization, actor, request);
        } catch (VclException failure) {
            if ("SNAPSHOT_EXPIRED".equals(failure.code())) throw new VclException(
                "EVIDENCE_EXPIRED",
                ""
            );
            throw failure;
        }
    }

    PreparedCorrection reconstructHistoricalWindow(
        String authorization,
        UUID actor,
        HistoricalWindowComparisonRequest request
    ) {
        if (
            request == null ||
            request.account() == null ||
            request.epoch() == null ||
            request.measure() == null ||
            request.corrected() == null ||
            request.corrected().isEmpty() ||
            request.corrected().size() > 2
        ) {
            throw new VclException("WINDOW_COMPARISON_INVALID", "");
        }
        var deployment = deployments
            .find(request.account(), request.epoch())
            .filter(
                value ->
                    value.actor().equals(actor) &&
                    value.acceptsExistingWork() &&
                    value.start() != null
            )
            .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        var bundle = compilationReads.bundle(deployment.account(), deployment.bundle());
        if (!deployment.bundleDigest().equals(bundle.digest())) throw new VclException(
            "DEPLOYMENT_BUNDLE_CHANGED",
            ""
        );
        var document = compilationReads.document(bundle);
        var start = snapshots.restoreStartCheckpoint(
            authorization,
            actor,
            deployment.account(),
            deployments,
            deployment.epoch(),
            document,
            compiler,
            Long.MAX_VALUE
        );
        if (
            start.source().sources().size() != 1 ||
            !start.references().keySet().equals(request.corrected().keySet())
        ) {
            throw new VclException("WINDOW_COMPARISON_INVALID", "");
        }
        var historical = keyed.historicalWindow(deployment, request.measure());
        var result = parser.readJson(
            historical.output().payload().payload().getBytes(StandardCharsets.UTF_8)
        );
        if (
            !"vcl-interval-final-measure-1".equals(result.path("profile").textValue()) ||
            !result.path("finalized").isBoolean() ||
            !result.path("finalized").booleanValue()
        ) {
            throw new VclException("WINDOW_MEASURE_UNAVAILABLE", "");
        }
        var schedule = IntervalWindowSchedule.recipe(historical.schedule());
        var window = schedule.window();
        if (
            !schedule.node().equals(result.path("node").textValue()) ||
            !window.startInclusive().toString().equals(result.path("windowStart").textValue()) ||
            !window.endExclusive().toString().equals(result.path("windowEnd").textValue()) ||
            !IntervalWindowSchedule.occurrence(window).equals(
                historical.key().occurrenceDigest()
            ) ||
            schedule.subjectLiteral() == null
        ) throw new VclException("WINDOW_MEASURE_CHANGED", "");
        var source = start.source().sources().getFirst();
        var cut = new SourceCaptureSnapshot.Cut(
            result.path("checkpoint").textValue(),
            result.path("cutDigest").textValue()
        );
        var target = new SourceBindingSnapshots.Target(source.binding(), source.schemaRevision());
        // Compile with the activation cut, then evaluate at the measure's retained cut.
        var snapshot = snapshots.captureJournalCut(
            authorization,
            actor,
            deployment.account(),
            document,
            target,
            source.cut()
        );
        var originalPins = result.has("referenceOverride")
            ? RuleStartCheckpointRecipe.referencePins(result.path("referenceOverride"))
            : start.references();
        if (!originalPins.keySet().equals(start.references().keySet())) throw new VclException(
            "INTERVAL_REFERENCE_CHANGED",
            ""
        );
        var original = snapshots.prepareLookups(
            snapshot,
            compiler,
            authorization,
            RuleStartCheckpointRecipe.selections(originalPins)
        );
        if (!original.referenceStartPins().equals(originalPins)) throw new VclException(
            "INTERVAL_REFERENCE_CHANGED",
            ""
        );
        UUID proof = UUID.fromString(result.path("proof").textValue());
        String proofDigest = result.path("coverageDigest").textValue();
        var originalValue = evaluateHistoricalWindow(
            original,
            historical.key(),
            schedule,
            cut,
            proof,
            proofDigest
        ).value();
        if (
            !originalValue.inputDigest().equals(result.path("inputDigest").textValue()) ||
            !originalValue.status().equals(result.path("status").textValue()) ||
            !java.util.Objects.equals(originalValue.reason(), result.path("reason").textValue()) ||
            !java.util.Objects.equals(
                originalValue.literal(),
                result.path("literal").isNull() ? null : result.path("literal")
            )
        ) {
            throw new VclException("WINDOW_MEASURE_CHANGED", "");
        }
        var corrected = snapshots.prepareLookups(
            snapshot,
            compiler,
            authorization,
            compilationReads.selections(request.corrected())
        );
        var correctedValue = evaluateHistoricalWindow(
            corrected,
            historical.key(),
            schedule,
            cut,
            proof,
            proofDigest
        );
        snapshot.reauthorize();
        if (
            deployments
                .find(deployment.account(), deployment.epoch())
                .filter(deployment::equals)
                .isEmpty()
        ) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        var comparison = new WindowComparison(cut.digest(), originalValue, correctedValue.value());
        var audit = JSON.createObjectNode()
            .put("profile", "VCL_REVIEWED_WINDOW_CORRECTION/1")
            .put("actor", actor.toString())
            .put("account", deployment.account().toString())
            .put("rule", deployment.rule().toString())
            .put("epoch", deployment.epoch().toString())
            .put("deploymentRevision", Long.toString(deployment.revision()))
            .put("bundleDigest", deployment.bundleDigest())
            .put("keyDigest", historical.key().digest())
            .put("originalMeasure", request.measure().toString())
            .put("originalMeasureDigest", historical.output().digest())
            .put("checkpoint", cut.head())
            .put("proof", proof.toString())
            .put("proofDigest", proofDigest);
        audit.set("comparison", JSON.valueToTree(comparison));
        audit.set("correctedReferences", JSON.valueToTree(corrected.referenceStartPins()));
        String payload = VclCanonical.encode(audit);
        return new PreparedCorrection(
            deployment,
            historical,
            comparison,
            payload,
            VclCanonical.sha256(payload),
            schedule,
            target,
            correctedValue,
            snapshot,
            result,
            corrected
        );
    }

    HistoricalEvaluation evaluateHistoricalWindow(
        RulePreparedLookups lookup,
        KeyedStateStore.Key key,
        IntervalWindowSchedule.Recipe schedule,
        SourceCaptureSnapshot.Cut cut,
        UUID proof,
        String proofDigest
    ) {
        long checkpoint = com.voltcanary.vcl.VclObservationReadiness.checkpoint(cut.head());
        var operator = lookup
            .compilation()
            .graph()
            .stream()
            .filter(node -> schedule.node().equals(node.id()))
            .findFirst()
            .orElseThrow(() -> new VclException("WINDOW_OPERATOR_REQUIRED", ""));
        var subject = ScalarValue.known(parser, schedule.subjectLiteral());
        if ("window_aggregate".equals(operator.method())) {
            var batch = lookup.journalAggregate(schedule.node());
            if (
                !batch.aggregateSubjectDigest(subject).equals(key.subjectDigest())
            ) throw new VclException("WINDOW_SUBJECT_INVALID", "");
            var evaluated = batch
                .evaluateCovered(
                    subject,
                    schedule.window(),
                    schedule.window().endExclusive(),
                    checkpoint,
                    (from, until) -> proof
                )
                .orElseThrow(() -> new VclException("WINDOW_FRONTIER_PENDING", ""));
            if (!cut.equals(evaluated.cut())) throw new VclException(
                "WINDOW_INPUT_CHECKPOINT_MISMATCH",
                ""
            );
            if (!evaluated.proof().eventTimeDigest().equals(proofDigest)) throw new VclException(
                "WINDOW_COVERAGE_CHANGED",
                ""
            );
            var result = new RuleCompilationSnapshot.WindowPreview(
                evaluated.inputDigest(),
                evaluated.value()
            );
            return new HistoricalEvaluation(
                ReviewedWindowComparisons.comparedWindow(lookup, result),
                evaluated.decision(),
                evaluated.proof(),
                true
            );
        } else if ("interval_union_window".equals(operator.method())) {
            if (
                !VclWindowSessionKernel.identity(subject).equals(key.subjectDigest())
            ) throw new VclException("WINDOW_SUBJECT_INVALID", "");
            var evaluated = lookup
                .finalizeIntervalUnion(
                    schedule.node(),
                    key.subjectDigest(),
                    schedule.window(),
                    lookup.referenceStartPins(),
                    checkpoint,
                    (from, until) -> proof
                )
                .orElseThrow(() -> new VclException("WINDOW_FRONTIER_PENDING", ""));
            if (
                !cut.digest().equals(evaluated.cutDigest()) ||
                !cut.head().equals(evaluated.checkpoint())
            ) {
                throw new VclException("WINDOW_INPUT_CHECKPOINT_MISMATCH", "");
            }
            if (!evaluated.proof().eventTimeDigest().equals(proofDigest)) throw new VclException(
                "WINDOW_COVERAGE_CHANGED",
                ""
            );
            var decision = lookup
                .intervalDecision(schedule.node(), subject)
                .evaluate(evaluated.result().localValue());
            return new HistoricalEvaluation(
                ReviewedWindowComparisons.comparedWindow(lookup, evaluated.result()),
                decision,
                evaluated.proof(),
                false
            );
        } else throw new VclException("WINDOW_OPERATOR_REQUIRED", "");
    }
}
