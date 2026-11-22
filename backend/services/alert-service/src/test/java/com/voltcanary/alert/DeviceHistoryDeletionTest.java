package com.voltcanary.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.alert.service.DeviceHistoryDeletion;
import com.voltcanary.common.web.JdbcDeviceFence;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class DeviceHistoryDeletionTest {

    @Test
    void bothHistoryPoliciesAreIdempotentAgainstTheMigratedSchemaAndLeaveFenceClosed() {
        var source = new DriverManagerDataSource(
            "jdbc:h2:mem:" +
                UUID.randomUUID() +
                ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
            "sa",
            ""
        );
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var history = new DeviceHistoryDeletion(jdbc);
        UUID retained = UUID.randomUUID(),
            purged = UUID.randomUUID(),
            untouched = UUID.randomUUID();
        for (UUID device : java.util.List.of(retained, purged, untouched)) {
            jdbc.update(
                "insert into alert_analytics_event_inbox(id,source_name,event_id,event_type,payload_json,status,next_attempt_at,created_at,updated_at) values(?,?,?,'ANOMALY_DETECTED',?,'FAILED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                "test",
                UUID.randomUUID(),
                "{\"deviceId\":\"" + device + "\"}"
            );
            jdbc.update(
                "insert into alerts(id,device_id,deduplication_key,title,description,severity,status,created_at) values(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                device,
                device.toString(),
                "Test alert",
                "Synthetic",
                "LOW",
                "OPEN"
            );
        }
        tx.executeWithoutResult(status -> history.close(retained, false));
        tx.executeWithoutResult(status -> history.close(purged, true));
        tx.executeWithoutResult(status -> history.close(purged, true));
        assertThat(
            jdbc.queryForObject(
                "select count(*) from alerts where device_id=?",
                Integer.class,
                purged
            )
        ).isZero();
        assertThat(
            jdbc.queryForObject(
                "select count(*) from alerts where device_id=?",
                Integer.class,
                retained
            )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "select count(*) from alerts where device_id=?",
                Integer.class,
                untouched
            )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "select status from alert_analytics_event_inbox where payload_json like ?",
                String.class,
                "%" + retained + "%"
            )
        ).isEqualTo("NO_ALERT");
        assertThat(
            jdbc.queryForObject(
                "select status from alert_analytics_event_inbox where payload_json like ?",
                String.class,
                "%" + untouched + "%"
            )
        ).isEqualTo("FAILED");
        var fence = new JdbcDeviceFence(jdbc);
        tx.executeWithoutResult(status -> {
            assertThat(fence.writable(retained)).isFalse();
            assertThat(fence.writable(purged)).isFalse();
            assertThat(fence.writable(UUID.randomUUID())).isTrue();
        });
    }
}
