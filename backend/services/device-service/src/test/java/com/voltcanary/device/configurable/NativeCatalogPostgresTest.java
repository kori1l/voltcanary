package com.voltcanary.device.configurable;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NativeCatalogPostgresTest extends NativeCatalogContract {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static DataSource source;
    @BeforeAll
    static void setup() {
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(source).execute("CREATE TABLE accounts(id UUID PRIMARY KEY,user_id UUID NOT NULL,status VARCHAR(32) NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        NativeTestMigrations.apply(source);
    }
    @Override DataSource source() { return source; }
}
