package com.voltcanary.analytics.vcl;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.junit.jupiter.api.Tag("docker")
class RuleDeploymentPostgresTest extends RuleDeploymentContract {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource source;

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V034__vcl_compilation_bundles.sql"),
            new ClassPathResource("db/migration/V040__vcl_rule_deployments.sql"),
            new ClassPathResource("db/migration/V041__vcl_deployment_actor.sql"),
            new ClassPathResource("db/migration/V042__vcl_legacy_deployment_fence.sql"),
            new ClassPathResource("db/migration/V043__vcl_deployment_cutover.sql"),
            new ClassPathResource("db/migration/V051__vcl_rule_pause.sql")
        ).execute(source);
    }

    @Override
    DataSource source() {
        return source;
    }
}
