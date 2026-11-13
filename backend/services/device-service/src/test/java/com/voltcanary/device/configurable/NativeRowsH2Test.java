package com.voltcanary.device.configurable;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;

class NativeRowsH2Test extends NativeRowsContract {
    private static final DataSource SOURCE = new DriverManagerDataSource("jdbc:h2:mem:native-rows;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
    @BeforeAll
    static void setup() {
        new JdbcTemplate(SOURCE).execute("CREATE TABLE accounts(id UUID PRIMARY KEY,user_id UUID NOT NULL,status VARCHAR(32) NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V024__configurable_native_datasets.sql"),
                new ClassPathResource("db/migration/V025__native_collection_reviews.sql"), new ClassPathResource("db/migration/V026__native_row_reviews.sql"),
                new ClassPathResource("db/migration/V028__native_query_snapshots.sql")).execute(SOURCE);
    }
    @Override DataSource source() { return SOURCE; }
}

