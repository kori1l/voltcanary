package com.voltcanary.analytics.vcl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.analytics.vcl.CompilationBundle;
import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerRuleCompilationSnapshotsTest {
    private final UUID actor = UUID.randomUUID(), account = UUID.randomUUID(), rule = UUID.randomUUID();
    private final UUID epoch = UUID.randomUUID(), bundleId = UUID.randomUUID(), grant = UUID.randomUUID();
    private final UUID binding = UUID.randomUUID(), policy = UUID.randomUUID();
    private final String digest = "a".repeat(64);
    private final JdbcRuleDeployments.Deployment active = new JdbcRuleDeployments.Deployment(actor, account, rule,
            epoch, bundleId, digest, grant, JdbcRuleDeployments.State.ACTIVE, 1);
    private final JdbcCompilationBundleStore bundles = mock(JdbcCompilationBundleStore.class);
    private final JdbcRuleDeployments deployments = mock(JdbcRuleDeployments.class);
    private final WorkerSourceCaptureSnapshots captures = mock(WorkerSourceCaptureSnapshots.class);
    private final WorkerGrantReads grants = mock(WorkerGrantReads.class);
    private final WorkerPolicyReads policies = mock(WorkerPolicyReads.class);
    private final KeyedStateStore store = mock(KeyedStateStore.class);
    private final VclCompiler compiler = mock(VclCompiler.class);
    private final KeyedStateStore.Key key = new KeyedStateStore.Key(account, rule, epoch, "b".repeat(64), "c".repeat(64));

    @Test void rejectsWrongDeploymentScopeBeforeReadingState() {
        var worker = new WorkerRuleCompilationSnapshots(bundles, deployments, captures, grants, policies);
        var foreign = new KeyedStateStore.Key(UUID.randomUUID(), rule, epoch, "b".repeat(64), "c".repeat(64));
        assertEquals("WORKER_TARGET_INVALID", assertThrows(VclException.class,
                () -> worker.restoreWindow(active, store, foreign, digest, compiler)).code());
        verifyNoInteractions(store, deployments, bundles, captures, grants, policies);
    }

    @Test void rejectsChangedDeploymentAndUnpreparedLookupBeforeOwnerReads() {
        var worker = new WorkerRuleCompilationSnapshots(bundles, deployments, captures, grants, policies);
        var revoked = new JdbcRuleDeployments.Deployment(actor, account, rule, epoch, bundleId, digest, grant,
                JdbcRuleDeployments.State.REVOKED, 2);
        when(deployments.find(account, epoch)).thenReturn(Optional.of(revoked));
        assertEquals("DEPLOYMENT_CHANGED", assertThrows(VclException.class,
                () -> worker.restoreWindow(active, store, key, digest, compiler)).code());
        verifyNoInteractions(store, captures, grants, policies);

        when(deployments.find(account, epoch)).thenReturn(Optional.of(active));
        var document = new VclParser().parse("""
                {"language":"VCL/1","kind":"RULE","spec":{"ruleId":"%s","revision":1,
                "name":"Worker guard","executionGrantId":"%s",
                "coverage":{"maxGapUs":"2000000","onUnknown":"UNKNOWN"},
                "decimalPolicy":{"precision":38,"scale":6,"rounding":"REJECT"},
                "nodes":[{"id":"source","method":"read_stream","inputs":{},"config":{"bindingId":"%s","schemaRevision":1}},
                {"id":"condition","method":"sustained","inputs":{"events":"source"},"config":{
                "subjectFieldId":"%s","predicate":{"op":"FIELD","fieldId":"%s"},
                "minimumDurationUs":"1000000","maxGapUs":"2000000"}},
                {"id":"alert","method":"emit_alert","inputs":{"decisions":"condition"},"config":{
                "severity":"WARNING","dedup":"RULE_SUBJECT_OCCURRENCE","evidencePolicyId":"%s"}}],
                "terminal":"alert","timing":{"allowedLatenessUs":"1000000","maxFutureSkewUs":"1000000","idleTimeoutUs":"5000000"}}}
                """.formatted(rule, grant, binding, UUID.randomUUID(), UUID.randomUUID(), policy));
        String canonical = VclCanonical.encode(document.json());
        var recipe = JsonNodeFactory.instance.objectNode().put("document", canonical).put("nodeId", "aggregate")
                .put("binding", binding.toString()).put("schemaRevision", "1").put("head", "5")
                .put("cutDigest", digest).put("membershipBudget", "15").put("subject", "cabinet-A")
                .put("start", "2026-11-03T00:00:00Z").put("end", "2026-11-03T01:00:00Z")
                .putNull("coverageProof");
        var state = JsonNodeFactory.instance.objectNode().put("profile", "vcl-durable-journal-window-3")
                .set("recipe", recipe);
        when(store.inspect(key, digest)).thenReturn(new KeyedStateStore.Snapshot(1, KeyedStateStore.State.parse(state.toString())));
        var bundle = mock(CompilationBundle.class);
        when(bundle.digest()).thenReturn(digest);
        var payload = JsonNodeFactory.instance.objectNode();
        var compiled = payload.putObject("compilation").put("canonicalDocument", canonical);
        compiled.putArray("graph").addObject().put("method", "lookup_asof");
        when(bundle.json()).thenReturn(payload);
        when(bundles.find(account, bundleId)).thenReturn(Optional.of(bundle));
        assertEquals("ASOF_SNAPSHOT_REQUIRED", assertThrows(VclException.class,
                () -> worker.restoreWindow(active, store, key, digest, compiler)).code());
        verifyNoInteractions(captures, grants, policies);
    }
}
