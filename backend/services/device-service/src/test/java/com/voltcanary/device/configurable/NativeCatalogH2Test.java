package com.voltcanary.device.configurable;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class NativeCatalogH2Test extends NativeCatalogContract {

    private static final DataSource SOURCE = new DriverManagerDataSource(
        "jdbc:h2:mem:native-catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "sa",
        ""
    );

    @BeforeAll
    static void setup() {
        new JdbcTemplate(SOURCE).execute(
            "CREATE TABLE accounts(id UUID PRIMARY KEY,user_id UUID NOT NULL,status VARCHAR(32) NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
        );
        NativeTestMigrations.apply(SOURCE);
    }

    @Override
    DataSource source() {
        return SOURCE;
    }
}
