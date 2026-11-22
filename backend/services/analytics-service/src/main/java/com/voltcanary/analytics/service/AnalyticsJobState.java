package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.AnalyticsJob;
import com.voltcanary.analytics.repository.AnalyticsJobRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Job state commits independently of a failed recalculation transaction. */
@Service
@RequiredArgsConstructor
public class AnalyticsJobState {

    private final AnalyticsJobRepository repository;
    private final DeviceHistoryDeletion deletions;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalyticsJob start(UUID id, String owner) {
        AnalyticsJob job = repository.findById(id).orElse(null);
        if (job == null || !deletions.writable(job.getDeviceId())) return null;
        job = repository.lockById(id).orElse(null);
        if (job == null) return null;
        entityManager.refresh(job);
        if (
            owner == null
                ? !"PENDING".equals(job.getStatus())
                : !"RUNNING".equals(job.getStatus()) ||
                      !owner.equals(job.getLockedBy()) ||
                      job.getLockedAt() == null ||
                      job.getLockedAt().isBefore(Instant.now().minusSeconds(900))
        ) return null;
        if (owner == null) {
            job.setLockedBy("sync:" + UUID.randomUUID());
            job.setLockedAt(Instant.now());
        }
        job.setStatus("RUNNING");
        job.setStartedAt(Instant.now());
        repository.saveAndFlush(job);
        // Use the database timestamp precision for subsequent exact-claim comparisons.
        entityManager.refresh(job);
        entityManager.detach(job);
        return job;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(
        AnalyticsJob expected,
        AnalyticsRecalculationService.RecalculationResult result,
        String error
    ) {
        if (!deletions.writable(expected.getDeviceId())) return;
        AnalyticsJob job = repository.lockById(expected.getId()).orElse(null);
        if (job != null) entityManager.refresh(job);
        if (
            job == null ||
            !"RUNNING".equals(job.getStatus()) ||
            !Objects.equals(job.getLockedBy(), expected.getLockedBy()) ||
            !Objects.equals(job.getLockedAt(), expected.getLockedAt()) ||
            job.getLockedAt() == null ||
            job.getLockedAt().isBefore(Instant.now().minusSeconds(900))
        ) return;
        job.setStatus(error != null ? "FAILED" : result.skipped() ? "SKIPPED" : "COMPLETED");
        job.setActiveKey(null);
        job.setCompletedAt(Instant.now());
        job.setLockedAt(null);
        job.setLockedBy(null);
        job.setErrorMessage(error);
        if (result != null) {
            job.setWindowStart(result.windowStart());
            job.setWindowEnd(result.windowEnd());
            job.setInputHash(result.inputHash());
            job.setSkipReason(result.skipReason());
        }
        repository.save(job);
    }
}
