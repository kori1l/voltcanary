package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsMetricProperties;
import com.voltcanary.analytics.domain.AnalyticsJob;
import com.voltcanary.analytics.repository.AnalyticsJobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsJobQueue {

    private final DeviceHistoryDeletion deletions;

    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "RUNNING");

    private final AnalyticsJobRepository jobRepository;
    private final AnalyticsMetricProperties metricProperties;
    private final MeterRegistry meterRegistry;

    public AnalyticsJobQueue(
        AnalyticsJobRepository jobRepository,
        AnalyticsMetricProperties metricProperties,
        MeterRegistry meterRegistry,
        DeviceHistoryDeletion deletions
    ) {
        this.jobRepository = jobRepository;
        this.metricProperties = metricProperties;
        this.meterRegistry = meterRegistry;
        this.deletions = deletions;
    }

    @Transactional
    public AnalyticsJob enqueueRecalculation(UUID deviceId, Instant eventTime) {
        if (!deletions.writable(deviceId)) {
            var canceled = new AnalyticsJob();
            canceled.setDeviceId(deviceId);
            canceled.setJobType("RECALCULATION");
            canceled.setStatus("CANCELLED");
            canceled.setCreatedAt(Instant.now());
            canceled.setCompletedAt(Instant.now());
            canceled.setErrorMessage("DEVICE_DELETED");
            return canceled;
        }
        Optional<AnalyticsJob> existing =
            jobRepository.findFirstByDeviceIdAndStatusInOrderByCreatedAtDesc(
                deviceId,
                ACTIVE_STATUSES
            );
        if (existing.isPresent()) {
            meterRegistry.counter("analytics.jobs.enqueued", "status", "reused").increment();
            return existing.get();
        }
        AnalyticsJob job = new AnalyticsJob();
        job.setId(UUID.randomUUID());
        job.setDeviceId(deviceId);
        job.setJobType("RECALCULATION");
        job.setStatus("PENDING");
        job.setActiveKey("ACTIVE");
        job.setCreatedAt(Instant.now());
        applyDeterministicWindow(job, eventTime);
        jobRepository.saveAndFlush(job);
        meterRegistry.counter("analytics.jobs.enqueued", "status", "pending").increment();
        return job;
    }

    private void applyDeterministicWindow(AnalyticsJob job, Instant eventTime) {
        if (eventTime == null) {
            return;
        }
        Instant windowEnd = eventTime.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        job.setWindowEnd(windowEnd);
        job.setWindowStart(windowEnd.minus(metricProperties.getDefaultWindow()));
    }
}
