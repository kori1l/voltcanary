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
class CompilationBundlePostgresTest extends BundleStoreContract {

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
            new ClassPathResource("db/migration/V034__vcl_compilation_bundles.sql")
        ).execute(source);
    }

    @Override
    DataSource dataSource() {
        return source;
    }
}
