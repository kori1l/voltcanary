package com.voltcanary.common.test;

import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

public final class PostgresTestHelper {

    private PostgresTestHelper() {
    }

    public static PostgreSQLContainer<?> createPostgresContainer() {
        return new PostgreSQLContainer<>(TestcontainersConfig.POSTGRES_IMAGE)
                .withDatabaseName("voltcanary_test")
                .withUsername("voltcanary")
                .withPassword("voltcanary");
    }

    public static Map<String, String> springDatasourceProperties(PostgreSQLContainer<?> postgres) {
        return Map.of(
                "spring.datasource.url", postgres.getJdbcUrl(),
                "spring.datasource.username", postgres.getUsername(),
                "spring.datasource.password", postgres.getPassword());
    }
}
