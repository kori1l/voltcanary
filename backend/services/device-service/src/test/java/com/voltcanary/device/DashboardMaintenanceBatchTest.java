package com.voltcanary.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.device.repository.DashboardMaintenanceRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.junit.jupiter.api.Tag("docker")
class DashboardMaintenanceBatchTest {

    static class CountingDataSource extends DriverManagerDataSource {

        final java.util.concurrent.atomic.AtomicInteger statements =
            new java.util.concurrent.atomic.AtomicInteger();

        CountingDataSource(String url, String user, String password) {
            super(url, user, password);
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection = super.getConnection();
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] { java.sql.Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) statements.incrementAndGet();
                    try {
                        return method.invoke(connection, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
            );
        }
    }

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void oneDeviceLookupForPagesOfOneTenAndFiftyAndNoForeignWindows() {
        var source = new CountingDataSource(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(
            "CREATE TABLE maintenance_operations(id uuid,account_id uuid,title text,start_at timestamp,end_at timestamp,cancelled_at timestamp,suppress_alerts boolean,exclude_from_analytics boolean,exclude_from_ml boolean)"
        );
        jdbc.execute(
            "CREATE TABLE maintenance_windows(operation_id uuid,account_id uuid,device_id uuid)"
        );
        UUID owner = UUID.randomUUID(),
            foreign = UUID.randomUUID(),
            device = UUID.randomUUID(),
            second = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-11T00:00:00Z");
        for (int i = 0; i < 50; i++) {
            UUID operation = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO maintenance_operations VALUES(?,?,?, ?,?,NULL,false,true,false)",
                operation,
                owner,
                "Operation" + i,
                Timestamp.from(now.plusSeconds(i)),
                Timestamp.from(now.plusSeconds(10000))
            );
            jdbc.update(
                "INSERT INTO maintenance_windows VALUES(?,?,?),(?,?,?),(?,?,?)",
                operation,
                owner,
                device,
                operation,
                owner,
                second,
                operation,
                foreign,
                UUID.randomUUID()
            );
        }
        var repository = new DashboardMaintenanceRepository(jdbc, new ObjectMapper());
        for (int size : List.of(1, 10, 50)) {
            source.statements.set(0);
            var request = new DashboardOwnerReadRequest(
                UUID.randomUUID(),
                owner,
                List.of(device, second),
                new DashboardOwnerReadRequest.TimeFrame(
                    now,
                    now.plusSeconds(20000),
                    now,
                    null,
                    null
                ),
                Set.of(),
                List.of(),
                size,
                0,
                "ACTIVE",
                List.of()
            );
            var page = repository.read(request, List.of(device, second));
            assertThat(page.path("total").asInt()).isEqualTo(50);
            assertThat(page.path("rows")).hasSize(size);
            page.path("rows").forEach(row -> {
                assertThat(row.path("deviceIds")).hasSize(2);
                assertThat(row.path("excludeFromAnalytics").asBoolean()).isTrue();
            });
            assertThat(source.statements.get()).isEqualTo(3);
        }
    }
}
