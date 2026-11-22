package com.voltcanary.telemetry.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "app.telemetry.timescale",
    name = "policy-enabled",
    havingValue = "true"
)
@RequiredArgsConstructor
@Slf4j
public class TimescalePolicyInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        String databaseProduct = jdbcTemplate.execute(
            (ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName()
        );
        if (databaseProduct == null || !databaseProduct.toLowerCase().contains("postgres")) {
            log.info("Skipping Timescale policy initialization for database={}", databaseProduct);
            return;
        }

        Boolean timescaleAvailable = jdbcTemplate.queryForObject(
            "select exists (select 1 from pg_extension where extname = 'timescaledb')",
            Boolean.class
        );
        if (!Boolean.TRUE.equals(timescaleAvailable)) {
            log.warn("Skipping Timescale policies because extension timescaledb is not installed");
            return;
        }

        jdbcTemplate.execute(
            """
            select create_hypertable(
                'telemetry.measurements',
                'recorded_at',
                if_not_exists => true,
                chunk_time_interval => interval '1 day')
            """
        );
        jdbcTemplate.execute(
            """
            alter table telemetry.measurements
            set (
                timescaledb.compress,
                timescaledb.compress_segmentby = 'device_id,metric_name',
                timescaledb.compress_orderby = 'recorded_at desc')
            """
        );
        jdbcTemplate.execute(
            """
            select add_compression_policy('telemetry.measurements', interval '7 days', if_not_exists => true)
            """
        );
        jdbcTemplate.execute(
            """
            select add_retention_policy('telemetry.measurements', interval '180 days', if_not_exists => true)
            """
        );
        jdbcTemplate.execute(
            "select create_hypertable('telemetry.positions', 'recorded_at', if_not_exists => true, chunk_time_interval => interval '1 day')"
        );
        jdbcTemplate.execute(
            "select add_retention_policy('telemetry.positions', interval '180 days', if_not_exists => true)"
        );
        log.info("Timescale policies initialized for telemetry.measurements");
    }
}
