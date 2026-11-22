package com.voltcanary.analytics.vcl;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class RuleDeploymentH2Test extends RuleDeploymentContract {

    private static final DataSource SOURCE = new DriverManagerDataSource(
        "jdbc:h2:mem:vcl-rule-deployments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "sa",
        ""
    );

    @BeforeAll
    static void migrate() {
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V034__vcl_compilation_bundles.sql"),
            new ClassPathResource("db/migration/V040__vcl_rule_deployments.sql"),
            new ClassPathResource("db/migration/V041__vcl_deployment_actor.sql"),
            new ClassPathResource("db/migration/V042__vcl_legacy_deployment_fence.sql"),
            new ClassPathResource("db/migration/V043__vcl_deployment_cutover.sql"),
            new ClassPathResource("db/migration/V051__vcl_rule_pause.sql")
        ).execute(SOURCE);
    }

    @Override
    DataSource source() {
        return SOURCE;
    }
}
