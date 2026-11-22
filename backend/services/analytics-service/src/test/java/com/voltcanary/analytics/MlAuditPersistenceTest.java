package com.voltcanary.analytics;

import com.voltcanary.analytics.domain.MlCanonicalMetricRow;
import com.voltcanary.analytics.domain.MlDataset;
import com.voltcanary.analytics.domain.MlDatasetSourceType;
import com.voltcanary.analytics.domain.MlDatasetStatus;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlDeploymentMode;
import com.voltcanary.analytics.domain.MlDeploymentScope;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationJob;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationStatus;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.dto.MlDeploymentPolicyRequest;
import com.voltcanary.analytics.repository.MlCanonicalMetricRowRepository;
import com.voltcanary.analytics.repository.MlDatasetRepository;
import com.voltcanary.analytics.repository.MlDatasetVersionRepository;
import com.voltcanary.analytics.repository.MlHorizonLabelGenerationJobRepository;
import com.voltcanary.analytics.repository.MlLabelRepository;
import com.voltcanary.analytics.service.MlDatasetQueryService;
import com.voltcanary.analytics.service.MlDeploymentPolicyService;
import com.voltcanary.analytics.service.MlHorizonLabelGenerationService;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "app.kafka.consume-enabled=false", "app.kafka.publish-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ml-audit-persistence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=true",
        "app.analytics.authorization.enabled=false", "app.ml.horizon-labels.recovery-initial-delay=1h"
})
class MlAuditPersistenceTest {
    @Autowired private MlDatasetRepository datasets;
    @Autowired private MlDatasetVersionRepository versions;
    @Autowired private MlCanonicalMetricRowRepository rows;
    @Autowired private MlLabelRepository labels;
    @MockitoSpyBean private MlHorizonLabelGenerationJobRepository jobs;
    @Autowired private MlHorizonLabelGenerationService generation;
    @Autowired private MlDatasetQueryService queries;
    @Autowired private MlDeploymentPolicyService deployment;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test void schedulerRecoversPersistedJobsAndCompletedJobsDoNotRegenerateLabels() {
        var version = dataset(UUID.randomUUID(), "Recovery");
        var job = queuedJob(version);
        generation.recoverQueuedJobs();
        var completed = jobs.findById(job.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(MlHorizonLabelGenerationStatus.COMPLETED);
        var original = labels.findByAccountIdAndDatasetVersionId(version.getAccountId(), version.getId());
        assertThat(original).isNotEmpty();
        generation.runJob(job.getId());
        generation.recoverQueuedJobs();
        assertThat(labels.findByAccountIdAndDatasetVersionId(version.getAccountId(), version.getId()))
                .extracting(label -> label.getId()).containsExactlyInAnyOrderElementsOf(original.stream().map(label -> label.getId()).toList());
    }

    @Test void completionFailureRollsBackGeneratedLabelsBeforeMarkingTheJobFailed() {
        var version = dataset(UUID.randomUUID(), "Rollback");
        var job = queuedJob(version);
        doThrow(new IllegalStateException("Controlled failure after label writes"))
                .when(jobs).save(argThat(candidate -> candidate != null && candidate.getId().equals(job.getId())
                        && candidate.getStatus() == MlHorizonLabelGenerationStatus.COMPLETED));
        generation.runJob(job.getId());
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(MlHorizonLabelGenerationStatus.FAILED);
        assertThat(labels.findByAccountIdAndDatasetVersionId(version.getAccountId(), version.getId())).isEmpty();
    }

    @Test void defaultDatasetListLoadsOnlyTheLatestVisibleVersionsInTwoQueries() {
        UUID account = UUID.randomUUID();
        var first = dataset(account, "First");
        var second = dataset(account, "Second");
        for (var base : List.of(first, second)) {
            for (int number = 2; number <= 12; number++) {
                version(base.getDatasetId(), account, number,
                        number == 12 ? MlDatasetStatus.ARCHIVED : MlDatasetStatus.READY);
            }
        }
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previous = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertThat(queries.list(account)).hasSize(2).allSatisfy(summary -> assertThat(summary.version()).isEqualTo(11));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
            assertThat(statistics.getEntityLoadCount()).isEqualTo(4);
        } finally {
            statistics.setStatisticsEnabled(previous);
        }
    }

    @Test void concurrentFirstPolicyWritesReturnTheSamePolicyInsteadOfLosingOneRequest() throws Exception {
        UUID account = UUID.randomUUID();
        var request = new MlDeploymentPolicyRequest(MlDeploymentScope.ACCOUNT, null, null, null,
                MlDeploymentMode.DISABLED, null, true, "Concurrency regression");
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return deployment.upsertPolicy(account, account, request); });
            var second = executor.submit(() -> { start.await(); return deployment.upsertPolicy(account, account, request); });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).id()).isEqualTo(second.get(15, TimeUnit.SECONDS).id());
        } finally {
            executor.shutdownNow();
        }
    }

    private MlDatasetVersion dataset(UUID account, String name) {
        var dataset = new MlDataset();
        dataset.setId(UUID.randomUUID());
        dataset.setAccountId(account);
        dataset.setName(name);
        dataset.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        dataset.setCreatedAt(Instant.now());
        dataset.setUpdatedAt(Instant.now());
        datasets.saveAndFlush(dataset);
        return version(dataset.getId(), account, 1, MlDatasetStatus.READY);
    }

    private MlDatasetVersion version(UUID dataset, UUID account, int number, MlDatasetStatus status) {
        var version = new MlDatasetVersion();
        version.setId(UUID.randomUUID());
        version.setDatasetId(dataset);
        version.setAccountId(account);
        version.setVersion(number);
        version.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
        version.setStatus(status);
        version.setSplitStrategy(MlTrainingSplitStrategy.TIME_BASED);
        version.setSplitConfigJson("{}");
        version.setFeatureConfigVersion("test");
        version.setCreatedAt(Instant.now());
        return versions.saveAndFlush(version);
    }

    private MlHorizonLabelGenerationJob queuedJob(MlDatasetVersion version) {
        UUID device = UUID.randomUUID();
        for (int day : List.of(0, 8)) {
            var row = new MlCanonicalMetricRow();
            row.setId(UUID.randomUUID());
            row.setAccountId(version.getAccountId());
            row.setDatasetVersionId(version.getId());
            row.setSourceType(MlDatasetSourceType.INTERNAL_TELEMETRY);
            row.setDeviceId(device);
            row.setTimestamp(Instant.parse("2026-07-01T00:00:00Z").plusSeconds(day * 86400L));
            row.setMetricName("power");
            row.setCanonicalMetricName("power");
            row.setNumericValue(100);
            row.setQualityFlags("[]");
            row.setMetadataJson("{}");
            row.setSourceLineage("{}");
            row.setCreatedAt(Instant.now());
            rows.saveAndFlush(row);
        }
        var job = new MlHorizonLabelGenerationJob();
        job.setId(UUID.randomUUID());
        job.setAccountId(version.getAccountId());
        job.setDatasetVersionId(version.getId());
        job.setHorizonDays(7);
        job.setGenerationVersion("audit-v1");
        job.setInputLineageHash("test-input");
        job.setIdempotencyKey(job.getId().toString());
        job.setStatus(MlHorizonLabelGenerationStatus.QUEUED);
        job.setSubmittedAt(Instant.now());
        return jobs.saveAndFlush(job);
    }
}
