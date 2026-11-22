package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.AnalyticsJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AnalyticsJobRunner {

    private final AnalyticsJobState state;
    private final AnalyticsRecalculationService recalculationService;
    private final MeterRegistry meterRegistry;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void run(UUID jobId) {
        run(jobId, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void run(UUID jobId, String owner) {
        AnalyticsJob job = state.start(jobId, owner);
        if (job == null) return;
        Timer.Sample timer = Timer.start(meterRegistry);
        MDC.put("jobId", job.getId().toString());
        MDC.put("deviceId", job.getDeviceId().toString());
        try {
            log.info(
                "analyticsJobStarted jobId={} deviceId={} jobType={}",
                job.getId(),
                job.getDeviceId(),
                job.getJobType()
            );
            AnalyticsRecalculationService.RecalculationResult result =
                recalculationService.recalculate(
                    job.getDeviceId(),
                    job.getWindowEnd() == null ? Instant.now() : job.getWindowEnd(),
                    job
                );
            state.finish(job, result, null);
            String terminalStatus = result.skipped() ? "skipped" : "completed";
            meterRegistry.counter("analytics.jobs.completed", "status", terminalStatus).increment();
            timer.stop(meterRegistry.timer("analytics.jobs.duration", "status", terminalStatus));
            log.info(
                "analyticsJobCompleted jobId={} deviceId={} status={}",
                job.getId(),
                job.getDeviceId(),
                terminalStatus
            );
        } catch (Exception exception) {
            log.warn(
                "analyticsJobFailed jobId={} deviceId={} reasonCode={}",
                job.getId(),
                job.getDeviceId(),
                exception.getClass().getSimpleName()
            );
            state.finish(job, null, message(exception));
            meterRegistry
                .counter(
                    "analytics.jobs.completed",
                    "status",
                    "failed",
                    "reason",
                    exception.getClass().getSimpleName()
                )
                .increment();
            timer.stop(meterRegistry.timer("analytics.jobs.duration", "status", "failed"));
        } finally {
            MDC.remove("jobId");
            MDC.remove("deviceId");
        }
    }

    private String message(Exception exception) {
        // Provider and SQL messages may contain credentials or private response bodies.
        return "Analytics recalculation failed (" + exception.getClass().getSimpleName() + ")";
    }
}
