package com.voltcanary.simulator;

import com.voltcanary.simulator.service.DeviceHistoryDeletion;
import com.voltcanary.common.web.JdbcDeviceFence;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class DeviceHistoryDeletionTest {
    @Test
    void bothHistoryPoliciesAreIdempotentAgainstTheMigratedSchemaAndLeaveFenceClosed() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var history = new DeviceHistoryDeletion(jdbc, org.mockito.Mockito.mock(com.voltcanary.simulator.runner.RunningSimulationRegistry.class));
        UUID retained = UUID.randomUUID(), purged = UUID.randomUUID();
        tx.executeWithoutResult(status -> history.close(retained, false));
        tx.executeWithoutResult(status -> history.close(purged, true));
        tx.executeWithoutResult(status -> history.close(purged, true));
        var fence = new JdbcDeviceFence(jdbc);
        tx.executeWithoutResult(status -> {
            assertThat(fence.writable(retained)).isFalse();
            assertThat(fence.writable(purged)).isFalse();
            assertThat(fence.writable(UUID.randomUUID())).isTrue();
        });
    }
}
