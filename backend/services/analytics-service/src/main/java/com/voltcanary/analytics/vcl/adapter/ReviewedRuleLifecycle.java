package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRuleCutoverIntent;
import java.time.Duration;
import java.util.UUID;

/** Applies and pauses rules under fresh owner confirmation. */
final class ReviewedRuleLifecycle {

    private final JdbcRuleDeployments deployments;
    private final RuleCompilationSnapshots snapshots;
    private final ConfigurableOwnerReads alert;
    private final VclParser parser;
    private final VclCompiler compiler;
    private final ReviewedRuleCompilation compilationReads;

    ReviewedRuleLifecycle(
        JdbcRuleDeployments deployments,
        RuleCompilationSnapshots snapshots,
        ConfigurableOwnerReads alert,
        VclParser parser,
        VclCompiler compiler,
        ReviewedRuleCompilation compilationReads
    ) {
        this.deployments = deployments;
        this.snapshots = snapshots;
        this.alert = alert;
        this.parser = parser;
        this.compiler = compiler;
        this.compilationReads = compilationReads;
    }

    JdbcRuleDeployments.Deployment apply(
        String authorization,
        UUID actor,
        UUID reviewId,
        RuleCompilationSnapshots.StartCheckpoint checkpoint
    ) {
        var review = review(authorization, actor, reviewId);
        var intent = review.intent();
        if (checkpoint == null || !intent.checkpointDigest().equals(checkpoint.digest())) {
            throw new VclException("START_CHECKPOINT_CHANGED", "");
        }
        var bundle = compilationReads.bundle(review.account(), intent.bundleId());
        var root = bundle.json();
        var compiled = root.path("compilation");
        var document = parser.parse(compiled.path("canonicalDocument").textValue());
        var grantPins = root.at("/ownerValidation/execution/resourceRevisions");
        boolean grantPinned = false;
        for (var pin : grantPins) {
            if (
                "EXECUTION_GRANT".equals(pin.path("kind").textValue()) &&
                intent.grantId().toString().equals(pin.path("id").textValue()) &&
                review.grantEpoch() == pin.path("revision").longValue()
            ) grantPinned = true;
        }
        if (
            !bundle.digest().equals(intent.bundleDigest()) ||
            !grantPinned ||
            !intent.ruleId().toString().equals(document.spec().path("ruleId").textValue()) ||
            !intent
                .grantId()
                .toString()
                .equals(document.spec().path("executionGrantId").textValue())
        ) {
            throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        }
        snapshots
            .verifiedStartSnapshot(
                authorization,
                actor,
                review.account(),
                document,
                compiler,
                Long.MAX_VALUE,
                checkpoint,
                bundle
            )
            .verifyBundleOwners(bundle);
        checkpoint.verifyBundleInputs(bundle);
        compilationReads.verifyBundleCircles(authorization, bundle);
        var staged = deployments.stage(actor, bundle, intent.successorEpoch());
        Runnable renew = () -> {
            if (!review.equals(review(authorization, actor, reviewId))) throw new VclException(
                "REVIEW_CONFLICT",
                ""
            );
            compilationReads.verifyBundleCircles(authorization, bundle);
        };
        try {
            return snapshots.cutover(
                authorization,
                actor,
                review.account(),
                deployments,
                intent.predecessorEpoch(),
                intent.successorEpoch(),
                document,
                compiler,
                Long.MAX_VALUE,
                checkpoint,
                bundle,
                renew,
                staged.state() != JdbcRuleDeployments.State.STAGED
            );
        } catch (VclException pending) {
            if (!"EVALUATOR_READINESS_UNAVAILABLE".equals(pending.code())) throw pending;
            return deployments
                .find(review.account(), intent.successorEpoch())
                .orElseThrow(() -> pending);
        }
    }

    JdbcRuleDeployments.Deployment pause(String authorization, UUID actor, UUID reviewId) {
        var review = pauseReview(authorization, actor, reviewId);
        var intent = review.intent();
        return deployments.pause(review.account(), intent.epoch(), current -> {
            long reviewedRevision =
                current.state() == JdbcRuleDeployments.State.PAUSED
                    ? current.revision() - 1
                    : current.revision();
            if (
                !current.actor().equals(actor) ||
                !current.rule().equals(intent.ruleId()) ||
                !current.grant().equals(intent.grantId()) ||
                !current.bundleDigest().equals(intent.bundleDigest()) ||
                reviewedRevision != intent.revision() ||
                !review.equals(pauseReview(authorization, actor, reviewId))
            ) {
                throw new VclException("REVIEW_CONFLICT", "");
            }
        });
    }

    PauseReview pauseReview(String authorization, UUID actor, UUID id) {
        try {
            var json = parser.readJson(
                alert.confirmedRulePause(
                    authorization,
                    id,
                    System.nanoTime() + Duration.ofSeconds(10).toNanos()
                )
            );
            UUID account = UUID.fromString(json.path("accountId").textValue());
            var value = json.path("intent");
            var intent = new com.voltcanary.vcl.VclRulePauseIntent(
                UUID.fromString(value.path("ruleId").textValue()),
                UUID.fromString(value.path("grantId").textValue()),
                UUID.fromString(value.path("epoch").textValue()),
                value.path("revision").longValue(),
                value.path("bundleDigest").textValue()
            );
            String digest = json.path("reviewDigest").textValue();
            if (
                !id.toString().equals(json.path("id").textValue()) ||
                !actor.toString().equals(json.path("actorId").textValue()) ||
                json.path("confirmedAt").isNull() ||
                json.path("confirmedAt").isMissingNode() ||
                !intent.reviewDigest(account, actor, id).equals(digest)
            ) {
                throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            }
            return new PauseReview(account, intent, digest);
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }

    Review review(String authorization, UUID actor, UUID id) {
        try {
            var json = parser.readJson(
                alert.confirmedRuleCutover(
                    authorization,
                    id,
                    System.nanoTime() + Duration.ofSeconds(10).toNanos()
                )
            );
            UUID account = UUID.fromString(json.path("accountId").textValue());
            long grantEpoch = json.path("grantEpoch").longValue();
            var value = json.path("intent");
            var predecessor = value.path("predecessorEpoch");
            var intent = new VclRuleCutoverIntent(
                UUID.fromString(value.path("ruleId").textValue()),
                UUID.fromString(value.path("grantId").textValue()),
                predecessor.isNull() || predecessor.isMissingNode()
                    ? null
                    : UUID.fromString(predecessor.textValue()),
                UUID.fromString(value.path("successorEpoch").textValue()),
                UUID.fromString(value.path("bundleId").textValue()),
                value.path("bundleDigest").textValue(),
                value.path("checkpointDigest").textValue()
            );
            String digest = json.path("reviewDigest").textValue();
            if (
                !id.toString().equals(json.path("id").textValue()) ||
                !actor.toString().equals(json.path("actorId").textValue()) ||
                grantEpoch < 1 ||
                json.path("confirmedAt").isNull() ||
                json.path("confirmedAt").isMissingNode() ||
                !intent.reviewDigest(account, actor, id, grantEpoch).equals(digest)
            ) {
                throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            }
            return new Review(account, grantEpoch, intent, digest);
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new VclException("OWNER_SNAPSHOT_INVALID", "");
        }
    }

    private record Review(
        UUID account,
        long grantEpoch,
        VclRuleCutoverIntent intent,
        String digest
    ) {}

    private record PauseReview(
        UUID account,
        com.voltcanary.vcl.VclRulePauseIntent intent,
        String digest
    ) {}
}
