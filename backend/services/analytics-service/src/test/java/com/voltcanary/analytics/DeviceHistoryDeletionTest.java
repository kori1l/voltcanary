package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.analytics.service.DeviceHistoryDeletion;
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
                "insert into analytics_jobs(id,device_id,job_type,status,active_key,created_at) values(?,?,'RECALCULATION','PENDING','ACTIVE',CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                device
            );
            jdbc.update(
                "insert into device_baselines(id,device_id,baseline_json,calculated_at) values(?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                device,
                "{}"
            );
        }
        tx.executeWithoutResult(status -> history.close(retained, false));
        tx.executeWithoutResult(status -> history.close(purged, true));
        tx.executeWithoutResult(status -> history.close(purged, true));
        assertThat(
            jdbc.queryForObject(
                "select count(*) from device_baselines where device_id=?",
                Integer.class,
                purged
            )
        ).isZero();
        assertThat(
            jdbc.queryForObject(
                "select count(*) from device_baselines where device_id=?",
                Integer.class,
                retained
            )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "select count(*) from device_baselines where device_id=?",
                Integer.class,
                untouched
            )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "select status from analytics_jobs where device_id=?",
                String.class,
                retained
            )
        ).isEqualTo("CANCELLED");
        assertThat(
            jdbc.queryForObject(
                "select status from analytics_jobs where device_id=?",
                String.class,
                untouched
            )
        ).isEqualTo("PENDING");
        var fence = new JdbcDeviceFence(jdbc);
        tx.executeWithoutResult(status -> {
            assertThat(fence.writable(retained)).isFalse();
            assertThat(fence.writable(purged)).isFalse();
            assertThat(fence.writable(UUID.randomUUID())).isTrue();
        });
    }

    @Test
    void sharedMlRowsBlockPurgeUntilExplicitlyRemovedWithoutReopeningWriteFence() {
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
        UUID account = UUID.randomUUID(),
            dataset = UUID.randomUUID(),
            version = UUID.randomUUID(),
            device = UUID.randomUUID();
        jdbc.update(
            "insert into ml_datasets(id,account_id,name,source_type,created_at,updated_at) values(?,?,?,'INTERNAL_TELEMETRY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            dataset,
            account,
            "Shared dataset"
        );
        jdbc.update(
            "insert into ml_dataset_versions(id,dataset_id,account_id,version,source_type,status,split_strategy,split_config_json,feature_config_version,created_at) values(?,?,?,1,'INTERNAL_TELEMETRY','READY','TIME_BASED','{}','v1',CURRENT_TIMESTAMP)",
            version,
            dataset,
            account
        );
        jdbc.update(
            "insert into ml_canonical_metric_rows(id,account_id,dataset_version_id,source_type,device_id,timestamp,metric_name,canonical_metric_name,numeric_value,quality_flags,metadata_json,source_lineage,created_at) values(?,?,?,'INTERNAL_TELEMETRY',?,CURRENT_TIMESTAMP,'temperatureC','temperatureC',7,'[]','{}','{}',CURRENT_TIMESTAMP)",
            UUID.randomUUID(),
            account,
            version,
            device
        );
        tx.executeWithoutResult(status -> history.close(device, false));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            tx.executeWithoutResult(status -> history.close(device, true))
        ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        tx.executeWithoutResult(status -> assertThat(history.writable(device)).isFalse());
        assertThat(
            jdbc.queryForObject(
                "select count(*) from ml_canonical_metric_rows where device_id=?",
                Integer.class,
                device
            )
        ).isEqualTo(1);
        jdbc.update("delete from ml_canonical_metric_rows where device_id=?", device);
        tx.executeWithoutResult(status -> history.close(device, true));
    }
}
