package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.VclWorkerIdentityProperties;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclEvidencePolicyDescription;
import com.voltcanary.vcl.VclException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Renews a graph-declared evidence policy against its sealed revision and current grant. */
@Component
public final class WorkerPolicyReads {

    private final JdbcCompilationBundleStore bundles;
    private final JdbcRuleDeployments deployments;
    private final ConfigurableOwnerReads reads;
    private final VclWorkerAssertion signer;
    private final WorkerGrantReads grants;

    public WorkerPolicyReads(
        DataSource source,
        RestClient.Builder builder,
        AnalyticsDownstreamProperties properties,
        VclWorkerIdentityProperties identity,
        WorkerGrantReads grants
    ) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        var target = properties.getAlertService();
        reads = new ConfigurableOwnerReads(builder, target.getBaseUrl(), target.getTimeout());
        signer = identity.forAudience("alert-service");
        this.grants = grants;
    }

    public VclEvidencePolicyDescription read(UUID account, UUID epoch, UUID policy) {
        if (signer == null) throw new VclException("WORKER_IDENTITY_UNAVAILABLE", "");
        if (account == null || epoch == null || policy == null) throw new VclException(
            "WORKER_TARGET_INVALID",
            ""
        );
        var active = deployments
            .find(account, epoch)
            .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!active.permitsOwnerInspection()) throw new VclException("DEPLOYMENT_INACTIVE", "");
        var bundle = bundles
            .find(account, active.bundle())
            .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest())) throw new VclException(
            "DEPLOYMENT_BUNDLE_CHANGED",
            ""
        );
        var root = bundle.json();
        boolean declared = false;
        for (var node : root.at("/compilation/graph")) {
            if (
                "emit_alert".equals(node.path("method").textValue()) &&
                policy.toString().equals(node.at("/config/evidencePolicyId").textValue())
            ) declared = true;
        }
        if (!declared) throw new VclException("WORKER_POLICY_NOT_PINNED", "");
        String expected = null;
        for (var pin : root.at("/ownerValidation/execution/resourceRevisions")) {
            if (
                "EVIDENCE_POLICY".equals(pin.path("kind").textValue()) &&
                policy.toString().equals(pin.path("id").textValue()) &&
                pin.path("revision").longValue() == 1
            ) {
                expected = pin.path("snapshotDigest").textValue();
            }
        }
        if (expected == null || !expected.matches("[0-9a-f]{64}")) {
            throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        }
        var grant = grants.read(account, epoch);
        var path = "/internal/vcl/evidence-policies/" + policy + "?revision=1";
        var assertion = signer.sign(
            new VclWorkerAssertion.Scope(
                active.actor(),
                account,
                active.rule(),
                epoch,
                active.grant(),
                policy,
                "EVIDENCE_POLICY",
                VclWorkerAssertion.requestDigest("GET", path, new byte[0])
            ),
            20
        );
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        VclEvidencePolicyDescription result;
        try {
            result = VclEvidencePolicyDescription.parse(
                reads.workerEvidencePolicy(assertion, policy, deadline)
            );
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
        if (
            !policy.equals(result.id()) ||
            !account.equals(result.accountId()) ||
            !active.actor().equals(result.actorId()) ||
            !active.rule().equals(result.ruleId()) ||
            !active.grant().equals(result.grantId()) ||
            !grant.epoch().equals(result.grantEpoch()) ||
            !grant.definitionDigest().equals(result.grantDefinitionDigest()) ||
            !result.usable() ||
            !"ACTIVE".equals(result.status()) ||
            !Instant.now().isBefore(result.expiresAt())
        ) {
            throw new VclException("EVIDENCE_POLICY_INACTIVE", "");
        }
        String digest = VclCanonical.sha256(
            "EVIDENCE_POLICY/1:" +
                result.id() +
                ":" +
                result.accountId() +
                ":" +
                result.actorId() +
                ":" +
                result.ruleId() +
                ":" +
                result.grantId() +
                ":" +
                result.grantEpoch() +
                ":" +
                result.grantDefinitionDigest() +
                ":" +
                result.expiresAt() +
                ":" +
                result.definitionDigest()
        );
        if (!expected.equals(digest)) throw new VclException("EVIDENCE_POLICY_CHANGED", "");
        if (
            !active.equals(
                deployments
                    .find(account, epoch)
                    .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""))
            )
        ) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        grants.read(account, epoch);
        return result;
    }
}
