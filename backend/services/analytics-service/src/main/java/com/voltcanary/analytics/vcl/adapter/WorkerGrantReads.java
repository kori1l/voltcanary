package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.VclWorkerIdentityProperties;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclAutomationGrantDescription;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Renews the sealed execution grant through the alert owner without a user bearer. */
@Component
public final class WorkerGrantReads {
    private final JdbcCompilationBundleStore bundles;
    private final JdbcRuleDeployments deployments;
    private final ConfigurableOwnerReads reads;
    private final VclWorkerAssertion signer;

    public WorkerGrantReads(DataSource source, RestClient.Builder builder, AnalyticsDownstreamProperties properties,
            VclWorkerIdentityProperties identity) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        var target = properties.getAlertService();
        reads = new ConfigurableOwnerReads(builder, target.getBaseUrl(), target.getTimeout());
        signer = identity.forAudience("alert-service");
    }

    public VclAutomationGrantDescription read(UUID account, UUID epoch) {
        if (signer == null) throw new VclException("WORKER_IDENTITY_UNAVAILABLE", "");
        if (account == null || epoch == null) throw new VclException("WORKER_TARGET_INVALID", "");
        var active = deployments.find(account, epoch)
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", ""));
        if (!active.permitsOwnerInspection()) throw new VclException("DEPLOYMENT_INACTIVE", "");
        var bundle = bundles.find(account, active.bundle())
                .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(active.bundleDigest())) throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        var root = new VclParser().readJson(bundle.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var pins = root.at("/ownerValidation/execution/resourceRevisions");
        long grantEpoch = -1;
        String expected = null;
        for (var pin : pins) {
            if ("EXECUTION_GRANT".equals(pin.path("kind").textValue())
                    && active.grant().toString().equals(pin.path("id").textValue())) {
                grantEpoch = pin.path("revision").longValue();
                expected = pin.path("snapshotDigest").textValue();
            }
        }
        if (grantEpoch < 1 || expected == null || !expected.matches("[0-9a-f]{64}")) {
            throw new VclException("DEPLOYMENT_BUNDLE_CHANGED", "");
        }
        var path = "/internal/vcl/grants/" + active.grant() + "?grantEpoch=" + grantEpoch;
        var assertion = signer.sign(new VclWorkerAssertion.Scope(active.actor(), account, active.rule(), epoch,
                active.grant(), active.grant(), "GRANT_DESCRIPTION",
                VclWorkerAssertion.requestDigest("GET", path, new byte[0])), 20);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        VclAutomationGrantDescription result;
        try { result = VclAutomationGrantDescription.parse(reads.workerGrantDescription(
                assertion, active.grant(), grantEpoch, deadline)); }
        catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
        if (!result.id().equals(active.grant()) || !result.actorId().equals(active.actor())
                || !result.accountId().equals(account) || !result.ruleId().equals(active.rule())
                || !result.epoch().equals(Long.toString(grantEpoch)) || !"ACTIVE".equals(result.status())
                || !Instant.now().isBefore(result.expiresAt())) {
            throw new VclException("AUTOMATION_GRANT_INACTIVE", "");
        }
        String digest = VclCanonical.sha256("AUTOMATION_GRANT/1:" + result.id() + ":" + result.accountId() + ":"
                + result.actorId() + ":" + result.ruleId() + ":" + result.epoch() + ":" + result.status() + ":"
                + result.definitionDigest());
        if (!expected.equals(digest)) throw new VclException("GRANT_CHANGED", "");
        if (!active.equals(deployments.find(account, epoch)
                .orElseThrow(() -> new VclException("DEPLOYMENT_UNAVAILABLE", "")))) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
        return result;
    }
}
