package com.voltcanary.telemetry;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class VehicleMonitoringReviewsPostgresTest extends VehicleMonitoringReviewsTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",DATABASE::getUsername);
        registry.add("spring.datasource.password",DATABASE::getPassword);
    }
}
