package com.voltcanary.analytics;

import com.voltcanary.analytics.service.AnalyticsJobService;
import com.voltcanary.analytics.service.AnalyticsJobState;
import com.voltcanary.analytics.service.AnalyticsResultStore;
import com.voltcanary.analytics.service.DeviceMetadataClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false", "app.kafka.publish-enabled=false",
        "app.analytics.jobs.async=false", "app.analytics.jobs.worker-enabled=false",
        "app.maintenance.reconciliation.enabled=false", "app.analytics.outbox.reconciliation.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:analytics-transaction-boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.open-in-view=false", "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true"
})
class AnalyticsTransactionBoundaryTest {
    @Autowired private AnalyticsJobService jobs;
    @Autowired private AnalyticsJobState state;
    @Autowired private AnalyticsResultStore results;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private DeviceMetadataClient metadata;

    @Test
    void failedRemoteReadHasNoDatabaseTransactionAndPersistsFailedJob() {
        UUID device = UUID.randomUUID();
        when(metadata.metadata(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new IllegalStateException("metadata unavailable password=private-value");
        });
        var job = jobs.enqueueRecalculation(device);
        assertThat(job.status()).isEqualTo("FAILED");
        assertThat(job.errorMessage()).doesNotContain("private-value", "password=");
        assertThat(jdbc.queryForObject("select status from analytics_jobs where id=?", String.class, job.id())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from risk_scores where device_id=?", Integer.class, device)).isZero();
    }

    @Test
    void staleWorkerCannotCompleteJobOrCommitComputedResults() {
        UUID id = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into analytics_jobs(id,device_id,job_type,status,active_key,created_at,locked_at,locked_by) values(?,?,?,'RUNNING','ACTIVE',?,?,?)",
                id, device, "RECALCULATION", Timestamp.from(now), Timestamp.from(now), "worker-a");
        var old = state.start(id, "worker-a");
        assertThat(old).isNotNull();
        jdbc.update("update analytics_jobs set locked_by='worker-b',locked_at=? where id=?", Timestamp.from(now.plusSeconds(1)), id);
        state.finish(old, null, "late failure");
        assertThat(jdbc.queryForObject("select status from analytics_jobs where id=?", String.class, id)).isEqualTo("RUNNING");
        assertThat(results.save(device, old, now, List.of(), List.of(), List.of(), null, List.of(), List.of()))
                .isEqualTo(AnalyticsResultStore.Outcome.JOB_LEASE_LOST);
        assertThat(jdbc.queryForObject("select locked_by from analytics_jobs where id=?", String.class, id)).isEqualTo("worker-b");
    }
    @Test
    void successfulResultAndJobCompletionCommitTogether() {
        UUID id = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into analytics_jobs(id,device_id,job_type,status,active_key,created_at) values(?,?,?,'PENDING','ACTIVE',?)",
                id, device, "RECALCULATION", Timestamp.from(now));
        var claim = state.start(id, null);
        var risk = new com.voltcanary.analytics.domain.RiskScore();
        risk.setId(UUID.randomUUID());
        risk.setDeviceId(device);
        risk.setScore(0);
        risk.setLevel("LOW");
        risk.setCalculatedAt(now);
        risk.setWindowStart(now.minusSeconds(60));
        risk.setWindowEnd(now);
        risk.setInputHash("transaction-test");
        assertThat(results.save(device, claim, now, List.of(), List.of(), List.of(), risk, List.of(), List.of()))
                .isEqualTo(AnalyticsResultStore.Outcome.SAVED);
        assertThat(jdbc.queryForObject("select status from analytics_jobs where id=?", String.class, id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from risk_scores where device_id=?", Integer.class, device)).isEqualTo(1);
        state.finish(claim, null, "late failure");
        assertThat(jdbc.queryForObject("select status from analytics_jobs where id=?", String.class, id)).isEqualTo("COMPLETED");
    }

}
