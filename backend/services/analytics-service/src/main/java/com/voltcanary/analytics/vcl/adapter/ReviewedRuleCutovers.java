package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Facade for reviewed rule compilation, inspection, correction and lifecycle operations. */
@Service
public final class ReviewedRuleCutovers {

    private final ReviewedRuleCompilation compilationReads;
    private final ReviewedWindowComparisons windowComparisons;
    private final ReviewedHistoricalMeasures historicalMeasures;
    private final ReviewedWindowCorrections windowCorrections;
    private final ReviewedRuleLifecycle lifecycle;

    public record Lookup(String query, UUID intervalField) {}

    public record CompilationReceipt(
        UUID bundleId,
        String bundleDigest,
        RuleCompilationSnapshots.StartCheckpoint checkpoint
    ) {}

    public record Preview(
        UUID account,
        UUID bundle,
        Instant from,
        Instant boundary,
        List<RuleCompilationSnapshot.SourceStartProof> proofs,
        Map<String, Lookup> lookups
    ) {}

    public record WindowComparisonRequest(
        UUID account,
        UUID bundle,
        String node,
        String subjectLiteral,
        Instant from,
        Instant until,
        Map<String, Lookup> original,
        Map<String, Lookup> corrected
    ) {}

    public record ComparedWindow(
        String inputDigest,
        String status,
        String reason,
        JsonNode literal,
        List<UUID> evidence,
        Map<String, String> referenceManifestDigests
    ) {}

    public record WindowComparison(
        String sourceCutDigest,
        ComparedWindow original,
        ComparedWindow corrected
    ) {}

    public record WindowPreviewRequest(
        UUID account,
        UUID bundle,
        RuleCompilationSnapshots.StartCheckpoint checkpoint,
        String node,
        String subjectLiteral,
        Instant from,
        Instant until
    ) {}

    public record WindowPreview(String sourceCutDigest, ComparedWindow result) {}

    public record SessionPreviewRequest(
        UUID account,
        UUID bundle,
        RuleCompilationSnapshots.StartCheckpoint checkpoint,
        String node,
        UUID circle,
        String subjectLiteral,
        String occurrenceLiteral,
        Instant from,
        Instant until,
        UUID proof
    ) {}

    public record SessionPreview(
        String checkpoint,
        String status,
        String reason,
        JsonNode literal,
        com.voltcanary.analytics.vcl.VclWindowPlan.Window sessionWindow,
        UUID entry,
        UUID exit,
        UUID firstWitness,
        UUID lastWitness,
        List<UUID> positionWitnesses,
        List<UUID> evidence,
        String coverageDigest
    ) {}

    public record HistoricalWindowComparisonRequest(
        UUID account,
        UUID epoch,
        UUID measure,
        Map<String, Lookup> corrected
    ) {}

    public record HistoricalMeasureRequest(UUID account, UUID epoch, UUID measure) {}

    public record HistoricalMeasurePageRequest(UUID account, UUID epoch, UUID after) {}

    public record HistoricalMeasureChoice(
        UUID measure,
        String node,
        String from,
        String until,
        String status
    ) {}

    public record HistoricalMeasurePage(List<HistoricalMeasureChoice> items, UUID nextAfter) {}

    public record DeployedRuleRequest(UUID account, UUID epoch) {}

    public record DeployedRuleCheckpoint(
        UUID rule,
        UUID epoch,
        String state,
        CompilationReceipt compilation
    ) {}

    public record HistoricalMeasure(
        UUID measure,
        UUID rule,
        UUID epoch,
        String node,
        @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING
        )
        Instant from,
        @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING
        )
        Instant until,
        String status,
        String reason,
        JsonNode literal,
        List<UUID> evidence,
        UUID supersedes
    ) {}

    public record HistoricalWindowComparison(
        String confirmationDigest,
        WindowComparison comparison
    ) {}

    public record WindowCorrectionRequest(
        HistoricalWindowComparisonRequest window,
        String confirmationDigest,
        boolean updateAlert
    ) {}

    public record WindowCorrection(
        UUID correctionId,
        String confirmationDigest,
        WindowComparison comparison,
        UUID alertProposalId
    ) {}

    public ReviewedRuleCutovers(
        DataSource source,
        RuleCompilationSnapshots snapshots,
        RestClient.Builder builder,
        AnalyticsDownstreamProperties properties
    ) {
        var bundles = new JdbcCompilationBundleStore(source);
        var dependencies = new NativeDatasetDependencies(source);
        var deployments = new JdbcRuleDeployments(source, bundles);
        var keyed = new JdbcKeyedStateStore(source);

        var target = properties.getAlertService();
        var alert = new ConfigurableOwnerReads(builder, target.getBaseUrl(), target.getTimeout());
        var deviceTarget = properties.getDeviceService();
        var device = new ConfigurableOwnerReads(
            builder,
            deviceTarget.getBaseUrl(),
            deviceTarget.getTimeout()
        );

        var parser = new VclParser();
        var compiler = new VclCompiler(new VclRegistry(parser), VclUnits.standard());
        compilationReads = new ReviewedRuleCompilation(
            bundles,
            dependencies,
            snapshots,
            device,
            parser,
            compiler
        );
        windowComparisons = new ReviewedWindowComparisons(
            snapshots,
            parser,
            compiler,
            compilationReads
        );
        historicalMeasures = new ReviewedHistoricalMeasures(
            deployments,
            keyed,
            snapshots,
            parser,
            compiler,
            compilationReads
        );
        windowCorrections = new ReviewedWindowCorrections(
            deployments,
            keyed,
            snapshots,
            alert,
            parser,
            compiler,
            compilationReads
        );
        lifecycle = new ReviewedRuleLifecycle(
            deployments,
            snapshots,
            alert,
            parser,
            compiler,
            compilationReads
        );
    }

    public JsonNode circleDependencies(String authorization, UUID actor, UUID id, long revision) {
        return compilationReads.circleDependencies(authorization, actor, id, revision);
    }

    public RuleCompilationSnapshots.StartCheckpoint preview(
        String authorization,
        UUID actor,
        Preview preview
    ) {
        return compilationReads.preview(authorization, actor, preview);
    }

    public SessionPreview previewSession(
        String authorization,
        UUID actor,
        SessionPreviewRequest request
    ) {
        return compilationReads.previewSession(authorization, actor, request);
    }

    public CompilationReceipt compile(String authorization, UUID actor, byte[] body) {
        return compilationReads.compile(authorization, actor, body);
    }

    public WindowPreview previewWindow(
        String authorization,
        UUID actor,
        WindowPreviewRequest request
    ) {
        return windowComparisons.previewWindow(authorization, actor, request);
    }

    public WindowComparison compareWindows(
        String authorization,
        UUID actor,
        WindowComparisonRequest request
    ) {
        return windowComparisons.compareWindows(authorization, actor, request);
    }

    public HistoricalMeasure readHistoricalMeasure(
        String authorization,
        UUID actor,
        HistoricalMeasureRequest request
    ) {
        return historicalMeasures.readHistoricalMeasure(authorization, actor, request);
    }

    public HistoricalMeasurePage listHistoricalMeasures(
        String authorization,
        UUID actor,
        HistoricalMeasurePageRequest request
    ) {
        return historicalMeasures.listHistoricalMeasures(authorization, actor, request);
    }

    public DeployedRuleCheckpoint deployedRuleCheckpoint(
        String authorization,
        UUID actor,
        DeployedRuleRequest request
    ) {
        return historicalMeasures.deployedRuleCheckpoint(authorization, actor, request);
    }

    public HistoricalWindowComparison compareHistoricalWindow(
        String authorization,
        UUID actor,
        HistoricalWindowComparisonRequest request
    ) {
        return windowCorrections.compareHistoricalWindow(authorization, actor, request);
    }

    public WindowCorrection publishHistoricalWindow(
        String authorization,
        UUID actor,
        WindowCorrectionRequest request
    ) {
        return windowCorrections.publishHistoricalWindow(authorization, actor, request);
    }

    public JdbcRuleDeployments.Deployment apply(
        String authorization,
        UUID actor,
        UUID reviewId,
        RuleCompilationSnapshots.StartCheckpoint checkpoint
    ) {
        return lifecycle.apply(authorization, actor, reviewId, checkpoint);
    }

    public JdbcRuleDeployments.Deployment pause(String authorization, UUID actor, UUID reviewId) {
        return lifecycle.pause(authorization, actor, reviewId);
    }
}
