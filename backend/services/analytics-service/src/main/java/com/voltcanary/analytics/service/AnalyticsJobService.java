package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsJobProperties;
import com.voltcanary.analytics.domain.AnalyticsJob;
import com.voltcanary.analytics.dto.AnalyticsJobResponse;
import com.voltcanary.analytics.repository.AnalyticsJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AnalyticsJobService {

    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "RUNNING");

    private final AnalyticsJobRepository jobRepository;
    private final AnalyticsJobRunner jobRunner;
    private final boolean asyncJobs;
    private final MeterRegistry meterRegistry;

    public AnalyticsJobService(
            AnalyticsJobRepository jobRepository,
            AnalyticsJobRunner jobRunner,
            AnalyticsJobProperties jobProperties,
            MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.jobRunner = jobRunner;
        this.asyncJobs = jobProperties.isAsync();
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public AnalyticsJobResponse enqueueRecalculation(UUID deviceId) {
        Optional<AnalyticsJob> existing = jobRepository.findFirstByDeviceIdAndStatusInOrderByCreatedAtDesc(
                deviceId,
                ACTIVE_STATUSES);
        if (existing.isPresent()) {
            meterRegistry.counter("analytics.jobs.enqueued", "status", "reused").increment();
            return toResponse(existing.get());
        }
        AnalyticsJob job = new AnalyticsJob();
        job.setId(UUID.randomUUID());
        job.setDeviceId(deviceId);
        job.setJobType("RECALCULATION");
        job.setStatus("PENDING");
        job.setCreatedAt(Instant.now());
        jobRepository.saveAndFlush(job);
        meterRegistry.counter("analytics.jobs.enqueued", "status", "pending").increment();
        if (asyncJobs) {
            UUID jobId = job.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    jobRunner.runAsync(jobId);
                }
            });
        } else {
            jobRunner.run(job.getId());
        }
        return toResponse(jobRepository.findById(job.getId()).orElse(job));
    }

    @Transactional(readOnly = true)
    public Optional<AnalyticsJobResponse> find(UUID jobId) {
        return jobRepository.findById(jobId).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AnalyticsJobResponse> list(UUID deviceId) {
        return jobRepository.findTop100ByDeviceIdOrderByCreatedAtDesc(deviceId).stream()
                .map(this::toResponse)
                .toList();
    }

    private AnalyticsJobResponse toResponse(AnalyticsJob job) {
        return new AnalyticsJobResponse(
                job.getId(),
                job.getDeviceId(),
                job.getJobType(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage());
    }
}
