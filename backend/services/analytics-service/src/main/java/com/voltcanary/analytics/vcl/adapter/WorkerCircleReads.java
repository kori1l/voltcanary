package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.VclWorkerIdentityProperties;
import com.voltcanary.analytics.vcl.SpatialFeatureSnapshot;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclCircleRevision;
import com.voltcanary.vcl.VclException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads only circle revisions explicitly pinned by an executable sealed RULE bundle. */
@Component
final class WorkerCircleReads {

    private final JdbcCompilationBundleStore bundles;
    private final JdbcRuleDeployments deployments;
    private final WorkerGrantReads grants;
    private final ConfigurableOwnerReads owner;
    private final VclWorkerAssertion signer;

    WorkerCircleReads(
        DataSource source,
        WorkerGrantReads grants,
        RestClient.Builder builder,
        AnalyticsDownstreamProperties properties,
        VclWorkerIdentityProperties identity
    ) {
        bundles = new JdbcCompilationBundleStore(source);
        deployments = new JdbcRuleDeployments(source, bundles);
        this.grants = grants;
        var target = properties.getDeviceService();
        owner = new ConfigurableOwnerReads(builder, target.getBaseUrl(), target.getTimeout());
        signer = identity.forAudience("device-service");
    }

    SpatialFeatureSnapshot read(
        JdbcRuleDeployments.Deployment deployment,
        UUID zone,
        long revision,
        String scope
    ) {
        return read(deployment, zone, revision, scope, false);
    }

    SpatialFeatureSnapshot current(
        JdbcRuleDeployments.Deployment deployment,
        UUID zone,
        long revision,
        String scope
    ) {
        try {
            return read(deployment, zone, revision, scope, true);
        } catch (VclException changed) {
            if (!"METADATA_SNAPSHOT_CHANGED".equals(changed.code())) throw changed;
            renew(deployment);
            return new SpatialFeatureSnapshot(scope, Map.of());
        }
    }

    private SpatialFeatureSnapshot read(
        JdbcRuleDeployments.Deployment deployment,
        UUID zone,
        long revision,
        String scope,
        boolean requireCurrent
    ) {
        if (signer == null) throw new VclException("WORKER_IDENTITY_UNAVAILABLE", "");
        if (!deployment.permitsOwnerInspection()) throw new VclException("DEPLOYMENT_INACTIVE", "");
        renew(deployment);
        var bundle = bundles
            .find(deployment.account(), deployment.bundle())
            .orElseThrow(() -> new VclException("DEPLOYMENT_BUNDLE_UNAVAILABLE", ""));
        if (!bundle.digest().equals(deployment.bundleDigest())) throw new VclException(
            "DEPLOYMENT_BUNDLE_CHANGED",
            ""
        );
        var pins = bundle.json().at("/ownerValidation/execution/resourceRevisions");
        String expected = null;
        long selected = 0;
        for (var pin : pins) {
            if (
                "SPATIAL_FEATURE".equals(pin.path("kind").textValue()) &&
                zone.toString().equals(pin.path("id").textValue()) &&
                (revision == 0 || revision == pin.path("revision").longValue())
            ) {
                if (expected != null) throw new VclException("WORKER_SPATIAL_NOT_PINNED", "");
                expected = pin.path("snapshotDigest").textValue();
                selected = pin.path("revision").longValue();
            }
        }
        if (expected == null || selected < 1) throw new VclException(
            "WORKER_SPATIAL_NOT_PINNED",
            ""
        );
        var path =
            "/internal/vcl/spatial-features/" +
            zone +
            "/circle?revision=" +
            selected +
            (requireCurrent ? "&current=true" : "");
        var assertion = signer.sign(
            new VclWorkerAssertion.Scope(
                deployment.actor(),
                deployment.account(),
                deployment.rule(),
                deployment.epoch(),
                deployment.grant(),
                zone,
                "SPATIAL_CIRCLE",
                VclWorkerAssertion.requestDigest("GET", path, new byte[0])
            ),
            20
        );
        try {
            var response = VclCircleRevision.parse(
                owner.workerCircleRevision(
                    assertion,
                    zone,
                    selected,
                    System.nanoTime() + Duration.ofSeconds(20).toNanos(),
                    requireCurrent
                )
            );
            if (
                !response.id().equals(zone) ||
                !response.accountId().equals(deployment.account()) ||
                response.revision() != selected ||
                !expected.equals(response.digest())
            ) {
                throw new VclException("SPATIAL_REVISION_CHANGED", "");
            }
            renew(deployment);
            var shape = response.geometry();
            return new SpatialFeatureSnapshot(
                scope,
                Map.of(
                    zone,
                    new SpatialFeatureSnapshot.Circle(
                        zone,
                        selected,
                        new SpatialFeatureSnapshot.Point(shape.longitude(), shape.latitude()),
                        new BigDecimal(shape.radiusMetres())
                    )
                )
            );
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
    }

    private void renew(JdbcRuleDeployments.Deployment expected) {
        grants.read(expected.account(), expected.epoch());
        if (
            deployments
                .find(expected.account(), expected.epoch())
                .filter(expected::equals)
                .isEmpty()
        ) {
            throw new VclException("DEPLOYMENT_CHANGED", "");
        }
    }
}
