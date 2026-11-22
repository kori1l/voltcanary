package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsJobProperties;
import com.voltcanary.analytics.domain.AnalyticsJob;
import com.voltcanary.analytics.dto.AnalyticsJobResponse;
import com.voltcanary.analytics.repository.AnalyticsJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AnalyticsJobService {

    private final AnalyticsJobRepository jobRepository;
    private final AnalyticsJobQueue queue;
    private final AnalyticsJobRunner jobRunner;
    private final AnalyticsJobProperties properties;

    public AnalyticsJobService(
        AnalyticsJobRepository jobRepository,
        AnalyticsJobQueue queue,
        AnalyticsJobRunner jobRunner,
        AnalyticsJobProperties properties
    ) {
        this.jobRepository = jobRepository;
        this.queue = queue;
        this.jobRunner = jobRunner;
        this.properties = properties;
    }

    public AnalyticsJobResponse enqueueRecalculation(UUID deviceId) {
        return enqueueRecalculation(deviceId, null);
    }

    public AnalyticsJobResponse enqueueRecalculation(UUID deviceId, Instant eventTime) {
        AnalyticsJob job = queue.enqueueRecalculation(deviceId, eventTime);
        UUID jobId = job.getId();
        if (!properties.isAsync() && "PENDING".equals(job.getStatus())) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            jobRunner.run(jobId);
                        }
                    }
                );
            } else {
                jobRunner.run(job.getId());
                job = jobRepository.findById(job.getId()).orElse(job);
            }
        }
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public Optional<AnalyticsJobResponse> find(UUID jobId) {
        return jobRepository.findById(jobId).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AnalyticsJobResponse> list(UUID deviceId) {
        return jobRepository
            .findTop100ByDeviceIdOrderByCreatedAtDesc(deviceId)
            .stream()
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
            job.getErrorMessage()
        );
    }
}
