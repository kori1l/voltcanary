package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.AnalyticsJob;
import com.voltcanary.analytics.repository.AnalyticsJobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AnalyticsJobRunner {
    private final AnalyticsJobRepository jobRepository;
    private final AnalyticsRecalculationService recalculationService;
    private final MeterRegistry meterRegistry;
    private final DeviceHistoryDeletion deletions;

    @org.springframework.transaction.annotation.Transactional
    public void run(UUID jobId) {
        AnalyticsJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !deletions.writable(job.getDeviceId())) return;
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
            job.setStatus(result.skipped() ? "SKIPPED" : "COMPLETED");
            job.setActiveKey(null);
            job.setCompletedAt(Instant.now());
            job.setWindowStart(result.windowStart());
            job.setWindowEnd(result.windowEnd());
            job.setInputHash(result.inputHash());
            job.setLockedAt(null);
            job.setLockedBy(null);
            job.setErrorMessage(null);
            job.setSkipReason(result.skipReason());
            jobRepository.save(job);
            String terminalStatus = result.skipped() ? "skipped" : "completed";
            meterRegistry.counter("analytics.jobs.completed", "status", terminalStatus).increment();
            timer.stop(meterRegistry.timer("analytics.jobs.duration", "status", terminalStatus));
            log.info("analyticsJobCompleted jobId={} deviceId={} status={}", job.getId(), job.getDeviceId(), job.getStatus());
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
