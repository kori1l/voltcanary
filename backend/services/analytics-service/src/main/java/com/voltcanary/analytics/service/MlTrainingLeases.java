package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import com.voltcanary.analytics.repository.MlTrainingRunRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short locked transitions; provider calls never hold a training row lock. */
@Service
@RequiredArgsConstructor
public class MlTrainingLeases {

    public static final class Lost extends RuntimeException {

        public Lost() {
            super("Training lease is no longer owned by this worker");
        }
    }

    private final MlTrainingRunRepository repository;
    private final EntityManager entityManager;

    @Transactional
    public Optional<MlTrainingRun> claim(String owner, Duration duration) {
        return repository
            .nextQueued(MlTrainingRunStatus.QUEUED, PageRequest.of(0, 1))
            .stream()
            .filter(run -> run.getStatus() == MlTrainingRunStatus.QUEUED)
            .findFirst()
            .map(run -> {
                Instant now = Instant.now();
                run.setStatus(MlTrainingRunStatus.RUNNING);
                if (run.getStartedAt() == null) run.setStartedAt(now);
                run.setLeaseOwner(owner);
                run.setLeaseExpiresAt(now.plus(duration));
                run.setHeartbeatAt(now);
                var saved = repository.saveAndFlush(run);
                entityManager.detach(saved);
                return saved;
            });
    }

    @Transactional
    public int recover() {
        var stale = repository.stale(
            MlTrainingRunStatus.RUNNING,
            Instant.now(),
            PageRequest.of(0, 100)
        );
        for (var run : stale) {
            run.setStatus(MlTrainingRunStatus.QUEUED);
            run.setLeaseOwner(null);
            run.setLeaseExpiresAt(null);
            run.setHeartbeatAt(null);
            run.setRetryCount(run.getRetryCount() + 1);
            run.setStaleRecoveredAt(Instant.now());
            run.setLogsSummary("Training run recovered from stale worker lease.");
        }
        repository.saveAll(stale);
        return stale.size();
    }

    @Transactional
    public void heartbeat(MlTrainingRun expected, Duration duration) {
        var actual = owned(expected);
        Instant now = Instant.now();
        actual.setHeartbeatAt(now);
        actual.setLeaseExpiresAt(now.plus(duration));
        repository.save(actual);
    }

    @Transactional
    public MlTrainingRun finish(MlTrainingRun expected, Runnable registerCandidate) {
        var actual = owned(expected);
        if (
            actual.isCancellationRequested() || actual.getStatus() == MlTrainingRunStatus.CANCELED
        ) {
            actual.setStatus(MlTrainingRunStatus.CANCELED);
            actual.setFailureReason(null);
            actual.setLogsSummary("Training canceled before candidate model registration.");
        } else {
            actual.setStatus(expected.getStatus());
            actual.setMetricsJson(expected.getMetricsJson());
            actual.setArtifactUri(expected.getArtifactUri());
            actual.setFailureReason(expected.getFailureReason());
            actual.setLogsSummary(expected.getLogsSummary());
            if (actual.getStatus() == MlTrainingRunStatus.SUCCEEDED) registerCandidate.run();
        }
        actual.setCompletedAt(Instant.now());
        actual.setLeaseOwner(null);
        actual.setLeaseExpiresAt(null);
        actual.setHeartbeatAt(null);
        return repository.save(actual);
    }

    private MlTrainingRun owned(MlTrainingRun expected) {
        String owner = expected.getLeaseOwner();
        var actual = repository.lockById(expected.getId()).orElseThrow(Lost::new);
        entityManager.refresh(actual);
        // A terminal result in `expected` has not yet been persisted. Authority comes from the stored row.
        if (
            !Objects.equals(actual.getLeaseOwner(), owner) ||
            actual.getLeaseOwner() == null ||
            actual.getLeaseExpiresAt() == null ||
            !actual.getLeaseExpiresAt().isAfter(Instant.now()) ||
            !Set.of(MlTrainingRunStatus.RUNNING, MlTrainingRunStatus.CANCELED).contains(
                actual.getStatus()
            )
        ) throw new Lost();
        return actual;
    }
}
