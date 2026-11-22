package com.voltcanary.analytics.vcl;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class KeyedStateH2Test extends KeyedWitnessContract {

    private static final DataSource SOURCE = new DriverManagerDataSource(
        "jdbc:h2:mem:vcl-key-state;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "sa",
        ""
    );

    @BeforeAll
    static void migrate() {
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V035__vcl_keyed_state.sql"),
            new ClassPathResource("db/migration/V036__vcl_keyed_timers.sql"),
            new ClassPathResource("db/migration/V037__vcl_keyed_outbox.sql"),
            new ClassPathResource("db/migration/V038__vcl_keyed_rekey_receipts.sql"),
            new ClassPathResource("db/migration/V039__vcl_keyed_input_receipt_digests.sql")
        ).execute(SOURCE);
    }

    @Override
    DataSource source() {
        return SOURCE;
    }
}
