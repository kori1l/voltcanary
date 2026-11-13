package com.voltcanary.analytics.vcl;

import com.voltcanary.analytics.vcl.adapter.JdbcCompilationBundleStore;
import com.voltcanary.analytics.vcl.adapter.JdbcRuleDeployments;
import com.voltcanary.vcl.VclException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

abstract class RuleDeploymentContract {
    abstract DataSource source();

    private JdbcRuleDeployments deployments() {
        return new JdbcRuleDeployments(source(), new JdbcCompilationBundleStore(source()));
    }

    private CompilationBundle bundle(UUID account) {
        var value = BundleFixtures.bundle(account, UUID.randomUUID(), "deployment-" + UUID.randomUUID());
        new JdbcCompilationBundleStore(source()).put(value);
        return value;
    }

    @Test void stagedIdentitySurvivesRestartAndRejectsEpochReuse() {
        var account = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var original = bundle(account);
        var epoch = UUID.randomUUID();
        var staged = deployments().stage(actor, original, epoch);
        assertEquals(JdbcRuleDeployments.State.STAGED, staged.state());
        assertEquals(staged, deployments().stage(actor, original, epoch));
        assertEquals(staged, deployments().find(account, epoch).orElseThrow());
        assertTrue(deployments().find(UUID.randomUUID(), epoch).isEmpty());
        assertEquals("DEPLOYMENT_EPOCH_REUSED", assertThrows(VclException.class,
                () -> deployments().stage(UUID.randomUUID(), original, epoch)).code());
        var changed = bundle(account);
        assertEquals("DEPLOYMENT_EPOCH_REUSED", assertThrows(VclException.class,
                () -> deployments().stage(actor, changed, epoch)).code());
    }

    @Test void freshOwnerFailureRollsBackActivationAndOnlyOneEpochCanBeActive() {
        var account = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var first = deployments().stage(actor, bundle(account), UUID.randomUUID());
        var second = deployments().stage(actor, bundle(account), UUID.randomUUID());
        assertEquals("OWNER_REVOKED", assertThrows(VclException.class,
                () -> deployments().activate(account, first.epoch(), ignored -> { throw new VclException("OWNER_REVOKED", ""); })).code());
        assertEquals(JdbcRuleDeployments.State.STAGED, deployments().find(account, first.epoch()).orElseThrow().state());
        var active = deployments().activate(account, first.epoch(), value -> assertEquals(first, value));
        assertEquals(JdbcRuleDeployments.State.ACTIVE, active.state());
        assertEquals(1, active.revision());
        assertEquals(active, deployments().activate(account, first.epoch(), value -> assertEquals(active, value)));
        assertEquals("DEPLOYMENT_STORAGE_UNAVAILABLE", assertThrows(VclException.class,
                () -> deployments().activate(account, second.epoch(), ignored -> { })).code());
        assertEquals(JdbcRuleDeployments.State.STAGED, deployments().find(account, second.epoch()).orElseThrow().state());
        assertEquals(1, deployments().active(10).stream().filter(value -> value.account().equals(account)).count());
        assertTrue(deployments().activePage(128, 0).contains(active));
        assertTrue(deployments().activePage(128, 128).isEmpty());
        assertEquals(JdbcRuleDeployments.State.REVOKED, deployments().revoke(account, first.epoch()).state());
        assertEquals(JdbcRuleDeployments.State.ACTIVE,
                deployments().activate(account, second.epoch(), ignored -> { }).state());
    }

    @Test void revokedEpochNeverReactivatesAndCorruptBundleIsUnavailable() {
        var account = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var staged = deployments().stage(actor, bundle(account), UUID.randomUUID());
        var revoked = deployments().revoke(account, staged.epoch());
        assertEquals(revoked, deployments().revoke(account, staged.epoch()));
        assertEquals("DEPLOYMENT_REVOKED", assertThrows(VclException.class,
                () -> deployments().activate(account, staged.epoch(), ignored -> { })).code());
        var next = deployments().stage(actor, bundle(account), UUID.randomUUID());
        new JdbcTemplate(source()).update("UPDATE vcl_rule_deployments SET grant_id=? WHERE account_id=? AND deployment_epoch=?",
                UUID.randomUUID(), account, next.epoch());
        assertEquals("DEPLOYMENT_BUNDLE_CHANGED", assertThrows(VclException.class,
                () -> deployments().find(account, next.epoch())).code());
        new JdbcTemplate(source()).update("UPDATE vcl_rule_deployments SET grant_id=? WHERE account_id=? AND deployment_epoch=?",
                next.grant(), account, next.epoch());
        new JdbcTemplate(source()).update("UPDATE vcl_rule_deployments SET bundle_digest=? WHERE account_id=? AND deployment_epoch=?",
                "0".repeat(64), account, next.epoch());
        assertEquals("DEPLOYMENT_BUNDLE_CHANGED", assertThrows(VclException.class,
                () -> deployments().find(account, next.epoch())).code());
    }

    @Test void revokeWaitsForLockedActivationAndLeavesNoActiveEpoch() throws Exception {
        var account = UUID.randomUUID();
        var staged = deployments().stage(UUID.randomUUID(), bundle(account), UUID.randomUUID());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var activating = pool.submit(() -> deployments().activate(account, staged.epoch(), ignored -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("authority wait timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var revoking = pool.submit(() -> deployments().revoke(account, staged.epoch()));
            release.countDown();
            assertEquals(JdbcRuleDeployments.State.ACTIVE, activating.get(15, TimeUnit.SECONDS).state());
            assertEquals(JdbcRuleDeployments.State.REVOKED, revoking.get(15, TimeUnit.SECONDS).state());
        }
        assertEquals(JdbcRuleDeployments.State.REVOKED, deployments().find(account, staged.epoch()).orElseThrow().state());
        assertTrue(deployments().active(128).stream().noneMatch(value -> value.epoch().equals(staged.epoch())));
    }
}
