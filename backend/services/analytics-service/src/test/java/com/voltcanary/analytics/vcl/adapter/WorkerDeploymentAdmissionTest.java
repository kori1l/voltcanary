package com.voltcanary.analytics.vcl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voltcanary.analytics.vcl.CompilationBundle;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerDeploymentAdmissionTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID rule = UUID.randomUUID();
    private final UUID epoch = UUID.randomUUID();
    private final UUID bundleId = UUID.randomUUID();
    private final UUID grant = UUID.randomUUID();
    private final UUID binding = UUID.randomUUID();
    private final UUID policy = UUID.randomUUID();
    private final String digest = "a".repeat(64);
    private final JdbcRuleDeployments.Deployment active = new JdbcRuleDeployments.Deployment(
            actor, account, rule, epoch, bundleId, digest, grant, JdbcRuleDeployments.State.ACTIVE, 1);
    private final JdbcCompilationBundleStore bundles = mock(JdbcCompilationBundleStore.class);
    private final JdbcRuleDeployments deployments = mock(JdbcRuleDeployments.class);
    private final WorkerGrantReads grants = mock(WorkerGrantReads.class);
    private final WorkerPolicyReads policies = mock(WorkerPolicyReads.class);
    private final WorkerSourceCaptureReads owners = mock(WorkerSourceCaptureReads.class);

    @Test void readsCurrentMachineOwnersAndKeepsUnqualifiedCoverageUnknown() {
        fixture();
        var admission = new WorkerDeploymentAdmission(bundles, deployments, grants, policies, owners);
        var result = admission.inspect(active);
        assertEquals(active, result.deployment());
        assertEquals(1, result.sources().size());
        assertFalse(result.ruleExecutionReady());
        verify(grants, times(2)).read(account, epoch);
        verify(policies).read(account, epoch, policy);
        verify(deployments, times(2)).find(account, epoch);
    }

    @Test void refusesDeploymentRevokedDuringOwnerReads() {
        fixture();
        var revoked = new JdbcRuleDeployments.Deployment(actor, account, rule, epoch, bundleId, digest, grant,
                JdbcRuleDeployments.State.REVOKED, 2);
        when(deployments.find(account, epoch)).thenReturn(Optional.of(active), Optional.of(revoked));
        var admission = new WorkerDeploymentAdmission(bundles, deployments, grants, policies, owners);
        assertEquals("DEPLOYMENT_CHANGED", assertThrows(VclException.class, () -> admission.inspect(active)).code());
    }

    private void fixture() {
        when(deployments.find(account, epoch)).thenReturn(Optional.of(active));
        var bundle = mock(CompilationBundle.class);
        when(bundle.digest()).thenReturn(digest);
        when(bundle.payload()).thenReturn("{\"compilation\":{\"graph\":["
                + "{\"method\":\"read_stream\",\"config\":{\"bindingId\":\"" + binding + "\",\"schemaRevision\":1}},"
                + "{\"method\":\"emit_alert\",\"config\":{\"evidencePolicyId\":\"" + policy + "\"}}]}}");
        when(bundles.find(account, bundleId)).thenReturn(Optional.of(bundle));
        var source = mock(VclSourceCaptureDescription.class);
        var readiness = mock(VclObservationReadiness.class);
        when(source.capture()).thenReturn(readiness);
        when(readiness.ruleExecutionReady()).thenReturn(false);
        when(owners.capture(account, epoch, binding, 1)).thenReturn(source);
    }
}
