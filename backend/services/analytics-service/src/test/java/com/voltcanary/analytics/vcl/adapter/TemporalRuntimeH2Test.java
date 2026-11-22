package com.voltcanary.analytics.vcl.adapter;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TemporalRuntimeH2Test extends TemporalRuntimeContract {

    private static final DataSource SOURCE = new DriverManagerDataSource(
        "jdbc:h2:mem:vcl-temporal-runtime;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "sa",
        ""
    );

    @BeforeAll
    static void migrate() throws Exception {
        migrate(SOURCE);
    }

    @Override
    DataSource source() {
        return SOURCE;
    }
}
