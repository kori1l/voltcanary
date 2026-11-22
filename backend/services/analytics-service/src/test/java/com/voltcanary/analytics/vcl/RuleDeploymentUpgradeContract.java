package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.*;

import com.voltcanary.analytics.vcl.adapter.JdbcCompilationBundleStore;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

abstract class RuleDeploymentUpgradeContract {

    abstract DataSource source();

    @Test
    void oldActiveRowWithoutActorIsRevokedBeforeWorkerCanDiscoverIt() {
        var source = source();
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V034__vcl_compilation_bundles.sql"),
            new ClassPathResource("db/migration/V040__vcl_rule_deployments.sql")
        ).execute(source);
        var account = UUID.randomUUID();
        var bundle = BundleFixtures.bundle(account, UUID.randomUUID(), "legacy-worker");
        new JdbcCompilationBundleStore(source).put(bundle);
        var jdbc = new JdbcTemplate(source);
        var rule = UUID.randomUUID();
        var epoch = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO vcl_rule_deployments(account_id,rule_id,deployment_epoch,bundle_id,bundle_digest,grant_id,state,active_slot)
            VALUES(?,?,?,?,?,?,'ACTIVE',1)
            """,
            account,
            rule,
            epoch,
            bundle.id(),
            bundle.digest(),
            UUID.randomUUID()
        );
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V041__vcl_deployment_actor.sql"),
            new ClassPathResource("db/migration/V042__vcl_legacy_deployment_fence.sql")
        ).execute(source);
        assertEquals(
            "REVOKED",
            jdbc.queryForObject(
                "SELECT state FROM vcl_rule_deployments WHERE account_id=? AND deployment_epoch=?",
                String.class,
                account,
                epoch
            )
        );
        assertNull(
            jdbc.queryForObject(
                "SELECT active_slot FROM vcl_rule_deployments WHERE account_id=? AND deployment_epoch=?",
                Integer.class,
                account,
                epoch
            )
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM vcl_rule_deployments WHERE state='ACTIVE'",
                Integer.class
            )
        );
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
            jdbc.update(
                """
                UPDATE vcl_rule_deployments SET state='ACTIVE',active_slot=1 WHERE account_id=? AND deployment_epoch=?
                """,
                account,
                epoch
            )
        );
    }
}
