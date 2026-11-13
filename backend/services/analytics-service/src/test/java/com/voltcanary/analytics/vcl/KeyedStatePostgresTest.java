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
class KeyedStatePostgresTest extends KeyedWitnessContract {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static DataSource source;
    @BeforeAll static void migrate() {
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V035__vcl_keyed_state.sql"),
                new ClassPathResource("db/migration/V036__vcl_keyed_timers.sql"),
                new ClassPathResource("db/migration/V037__vcl_keyed_outbox.sql"),
                new ClassPathResource("db/migration/V038__vcl_keyed_rekey_receipts.sql"),
                new ClassPathResource("db/migration/V039__vcl_keyed_input_receipt_digests.sql")).execute(source);
    }
    @Override DataSource source() { return source; }
}
