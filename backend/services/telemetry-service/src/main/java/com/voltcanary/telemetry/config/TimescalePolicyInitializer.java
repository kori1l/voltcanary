package com.voltcanary.telemetry.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.telemetry.timescale", name = "policy-enabled", havingValue = "true")
public class TimescalePolicyInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimescalePolicyInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public TimescalePolicyInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        String databaseProduct = jdbcTemplate.execute((ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
        if (databaseProduct == null || !databaseProduct.toLowerCase().contains("postgres")) {
            log.info("Skipping Timescale policy initialization for database={}", databaseProduct);
            return;
        }

        Boolean timescaleAvailable = jdbcTemplate.queryForObject(
                "select exists (select 1 from pg_extension where extname = 'timescaledb')",
                Boolean.class);
        if (!Boolean.TRUE.equals(timescaleAvailable)) {
            log.warn("Skipping Timescale policies because extension timescaledb is not installed");
            return;
        }

        jdbcTemplate.execute("""
                select create_hypertable(
                    'telemetry.measurements',
                    'recorded_at',
                    if_not_exists => true,
                    chunk_time_interval => interval '1 day')
                """);
        jdbcTemplate.execute("""
                alter table telemetry.measurements
                set (
                    timescaledb.compress,
                    timescaledb.compress_segmentby = 'device_id,metric_name',
                    timescaledb.compress_orderby = 'recorded_at desc')
                """);
        jdbcTemplate.execute("""
                select add_compression_policy('telemetry.measurements', interval '7 days', if_not_exists => true)
                """);
        jdbcTemplate.execute("""
                select add_retention_policy('telemetry.measurements', interval '180 days', if_not_exists => true)
                """);
        log.info("Timescale policies initialized for telemetry.measurements");
    }
}
