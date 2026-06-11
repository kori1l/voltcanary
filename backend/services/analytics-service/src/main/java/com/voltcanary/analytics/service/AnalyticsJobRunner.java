package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.AnalyticsJob;
import com.voltcanary.analytics.repository.AnalyticsJobRepository;
import java.time.Instant;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsJobRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsJobRunner.class);

    private final AnalyticsJobRepository jobRepository;
    private final AnalyticsRecalculationService recalculationService;
    private final MeterRegistry meterRegistry;

    public AnalyticsJobRunner(
            AnalyticsJobRepository jobRepository,
            AnalyticsRecalculationService recalculationService,
            MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.recalculationService = recalculationService;
        this.meterRegistry = meterRegistry;
    }

    public void run(UUID jobId) {
        AnalyticsJob job = jobRepository.findById(jobId).orElseThrow();
        Timer.Sample timer = Timer.start(meterRegistry);
        MDC.put("jobId", job.getId().toString());
        MDC.put("deviceId", job.getDeviceId().toString());
        job.setStatus("RUNNING");
        job.setStartedAt(Instant.now());
        jobRepository.saveAndFlush(job);
        try {
            log.info("analyticsJobStarted jobId={} deviceId={} jobType={}", job.getId(), job.getDeviceId(), job.getJobType());
            AnalyticsRecalculationService.RecalculationResult result = job.getWindowEnd() == null
                    ? recalculationService.recalculate(job.getDeviceId())
                    : recalculationService.recalculate(job.getDeviceId(), job.getWindowEnd());
            job.setStatus("COMPLETED");
            job.setActiveKey(null);
            job.setCompletedAt(Instant.now());
            job.setWindowStart(result.windowStart());
            job.setWindowEnd(result.windowEnd());
            job.setInputHash(result.inputHash());
            job.setLockedAt(null);
            job.setLockedBy(null);
            job.setErrorMessage(null);
            jobRepository.save(job);
            meterRegistry.counter("analytics.jobs.completed", "status", "completed").increment();
            timer.stop(meterRegistry.timer("analytics.jobs.duration", "status", "completed"));
            log.info("analyticsJobCompleted jobId={} deviceId={}", job.getId(), job.getDeviceId());
        } catch (Exception exception) {
            log.warn(
                    "analyticsJobFailed jobId={} deviceId={} reasonCode={}",
                    job.getId(),
                    job.getDeviceId(),
                    exception.getClass().getSimpleName());
            job.setStatus("FAILED");
            job.setActiveKey(null);
            job.setCompletedAt(Instant.now());
            job.setLockedAt(null);
            job.setLockedBy(null);
            job.setErrorMessage(message(exception));
            jobRepository.save(job);
            meterRegistry.counter("analytics.jobs.completed", "status", "failed", "reason", exception.getClass().getSimpleName()).increment();
            timer.stop(meterRegistry.timer("analytics.jobs.duration", "status", "failed"));
        } finally {
            MDC.remove("jobId");
            MDC.remove("deviceId");
        }
    }

    private String message(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
