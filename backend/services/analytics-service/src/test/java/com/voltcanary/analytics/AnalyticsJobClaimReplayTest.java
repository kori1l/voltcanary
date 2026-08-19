package com.voltcanary.analytics;

import com.voltcanary.analytics.config.AnalyticsJobProperties;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.service.AnalyticsJobRunner;
import com.voltcanary.analytics.service.AnalyticsJobWorker;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false",
        "app.kafka.publish-enabled=false",
        "app.analytics.authorization.enabled=false",
        "app.analytics.outbox.reconciliation.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:analytics-job-claim-replay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class AnalyticsJobClaimReplayTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private MlClient mlClient;

    @Test
    void secondWorkerSkipsFreshLeaseButReplaysStaleRunningJob() {
        UUID jobId = insertPendingJob();
        AnalyticsJobRunner runner = mock(AnalyticsJobRunner.class);
        AnalyticsJobWorker firstWorker = worker(runner);
        AnalyticsJobWorker secondWorker = worker(runner);

        firstWorker.runClaimedJobs();
        secondWorker.runClaimedJobs();

        verify(runner, times(1)).run(jobId);
        assertThat(status(jobId)).isEqualTo("RUNNING");
        assertThat(lockedAt(jobId)).isNotNull();

        jdbcTemplate.update(
                "update analytics_jobs set locked_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)),
                jobId);
        secondWorker.runClaimedJobs();

        verify(runner, times(2)).run(jobId);
    }

    private AnalyticsJobWorker worker(AnalyticsJobRunner runner) {
        AnalyticsJobProperties properties = new AnalyticsJobProperties();
        properties.setAsync(true);
        properties.setWorkerEnabled(true);
        return new AnalyticsJobWorker(jdbcTemplate, transactionTemplate, runner, properties);
    }

    private UUID insertPendingJob() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into analytics_jobs (
                    id,
                    device_id,
                    job_type,
                    status,
                    created_at,
                    completed_at,
                    started_at,
                    error_message,
                    active_key,
                    window_start,
                    window_end,
                    input_hash,
                    account_id,
                    locked_at,
                    locked_by
                ) values (?, ?, 'RECALCULATION', 'PENDING', ?, null, null, null, 'ACTIVE', null, null, null, null, null, null)
                """,
                id,
                UUID.randomUUID(),
                Timestamp.from(Instant.now().minusSeconds(60)));
        return id;
    }

    private String status(UUID id) {
        return jdbcTemplate.queryForObject("select status from analytics_jobs where id = ?", String.class, id);
    }

    private Instant lockedAt(UUID id) {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "select locked_at from analytics_jobs where id = ?",
                Timestamp.class,
                id);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
