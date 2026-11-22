package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.CompilationReceipt;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.DeployedRuleCheckpoint;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.DeployedRuleRequest;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.HistoricalMeasure;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.HistoricalMeasureChoice;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.HistoricalMeasurePage;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.HistoricalMeasurePageRequest;
import com.voltcanary.analytics.vcl.adapter.ReviewedRuleCutovers.HistoricalMeasureRequest;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Reads retained historical measures under fresh deployment authority. */
final class ReviewedHistoricalMeasures {

    private final JdbcRuleDeployments deployments;
    private final JdbcKeyedStateStore keyed;
    private final RuleCompilationSnapshots snapshots;
    private final VclParser parser;
    private final VclCompiler compiler;
    private final ReviewedRuleCompilation compilationReads;

    ReviewedHistoricalMeasures(
        JdbcRuleDeployments deployments,
        JdbcKeyedStateStore keyed,
        RuleCompilationSnapshots snapshots,
        VclParser parser,
        VclCompiler compiler,
        ReviewedRuleCompilation compilationReads
    ) {
        this.deployments = deployments;
        this.keyed = keyed;
        this.snapshots = snapshots;
        this.parser = parser;
        this.compiler = compiler;
        this.compilationReads = compilationReads;
    }

    record MeasureAuthority(
        JdbcRuleDeployments.Deployment deployment,
        RuleCompilationSnapshot snapshot,
        RuleCompilationSnapshots.StartCheckpoint checkpoint
    ) {}

    HistoricalMeasure readHistoricalMeasure(
        String authorization,
        UUID actor,
        HistoricalMeasureRequest request
    ) {
        if (
            request == null ||
            request.account() == null ||
            request.epoch() == null ||
            request.measure() == null
        ) {
            throw new VclException("WINDOW_MEASURE_REQUEST_INVALID", "");
        }
        var authority = measureAuthority(authorization, actor, request.account(), request.epoch());
        var deployment = authority.deployment();
        var saved = keyed.historicalWindow(deployment, request.measure());
        var result = parser.readJson(
            saved.output().payload().payload().getBytes(StandardCharsets.UTF_8)
        );
        if (
            !"vcl-interval-final-measure-1".equals(result.path("profile").textValue()) ||
            !result.path("finalized").isBoolean() ||
            !result.path("finalized").booleanValue()
        ) {
            throw new VclException("WINDOW_MEASURE_UNAVAILABLE", "");
        }
        var recipe = IntervalWindowSchedule.recipe(saved.schedule());
        var window = recipe.window();
        if (
            !recipe.node().equals(result.path("node").textValue()) ||
            !window.startInclusive().toString().equals(result.path("windowStart").textValue()) ||
            !window.endExclusive().toString().equals(result.path("windowEnd").textValue()) ||
            !IntervalWindowSchedule.occurrence(window).equals(saved.key().occurrenceDigest())
        ) {
            throw new VclException("WINDOW_MEASURE_CHANGED", "");
        }
        var evidence = authority
            .snapshot()
            .retainedWindowReferences(result, window.startInclusive(), Instant.now());
        verifyMeasureDeployment(deployment);
        UUID supersedes = result.hasNonNull("supersedes")
            ? UUID.fromString(result.path("supersedes").textValue())
            : null;
        return new HistoricalMeasure(
            request.measure(),
            deployment.rule(),
            deployment.epoch(),
            recipe.node(),
            window.startInclusive(),
            window.endExclusive(),
            result.path("status").textValue(),
            result.path("reason").textValue(),
            result.get("literal"),
            evidence,
            supersedes
        );
    }

    HistoricalMeasurePage listHistoricalMeasures(
        String authorization,
        UUID actor,
        HistoricalMeasurePageRequest request
    ) {
        if (request == null || request.account() == null || request.epoch() == null) {
            throw new VclException("WINDOW_MEASURE_REQUEST_INVALID", "");
        }
        var authority = measureAuthority(authorization, actor, request.account(), request.epoch());
        var rows = keyed.finalizedIntervalMeasures(authority.deployment(), request.after());
        var choices = rows
            .stream()
            .limit(25)
            .map(row -> {
                var result = parser.readJson(
                    row.state().payload().getBytes(StandardCharsets.UTF_8)
                );
                if (
                    !"vcl-interval-final-measure-1".equals(result.path("profile").textValue()) ||
                    !result.path("finalized").isBoolean() ||
                    !result.path("finalized").booleanValue() ||
                    !result.path("node").isTextual() ||
                    !result.path("windowStart").isTextual() ||
                    !result.path("windowEnd").isTextual() ||
                    !result.path("status").isTextual()
                ) {
                    throw new VclException("WINDOW_MEASURE_CHANGED", "");
                }
                return new HistoricalMeasureChoice(
                    row.id(),
                    result.path("node").textValue(),
                    result.path("windowStart").textValue(),
                    result.path("windowEnd").textValue(),
                    result.path("status").textValue()
                );
            })
            .toList();
        authority.snapshot().reauthorize();
        verifyMeasureDeployment(authority.deployment());
        return new HistoricalMeasurePage(
            choices,
            rows.size() > 25 ? choices.getLast().measure() : null
        );
    }

    DeployedRuleCheckpoint deployedRuleCheckpoint(
        String authorization,
        UUID actor,
        DeployedRuleRequest request
    ) {
        if (request == null || request.account() == null || request.epoch() == null) {
            throw new VclException("WINDOW_MEASURE_REQUEST_INVALID", "");
        }
        var authority = measureAuthority(authorization, actor, request.account(), request.epoch());
        var deployment = authority.deployment();
        authority.snapshot().reauthorize();
        verifyMeasureDeployment(deployment);
        return new DeployedRuleCheckpoint(
            deployment.rule(),
            deployment.epoch(),
            deployment.state().name(),
            new CompilationReceipt(
                deployment.bundle(),
                deployment.bundleDigest(),
                authority.checkpoint()
            )
        );
    }

    MeasureAuthority measureAuthority(String authorization, UUID actor, UUID account, UUID epoch) {
        var deployment = deployments
            .find(account, epoch)
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
        var checkpoint = snapshots.restoreStartCheckpoint(
            authorization,
            actor,
            account,
            deployments,
            epoch,
            document,
            compiler,
            Long.MAX_VALUE
        );
        var snapshot = snapshots.verifiedStartSnapshot(
            authorization,
            actor,
            account,
            document,
            compiler,
            Long.MAX_VALUE,
            checkpoint,
            bundle
        );
        snapshot.verifyBundleOwners(bundle);
        compilationReads.verifyBundleCircles(authorization, bundle);
        return new MeasureAuthority(deployment, snapshot, checkpoint);
    }

    void verifyMeasureDeployment(JdbcRuleDeployments.Deployment deployment) {
        if (
            deployments
                .find(deployment.account(), deployment.epoch())
                .filter(deployment::equals)
                .isEmpty()
        ) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
    }
}
