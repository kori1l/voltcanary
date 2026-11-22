package com.voltcanary.analytics.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.voltcanary.analytics.domain.MlTrainingRun;
import com.voltcanary.analytics.domain.MlTrainingRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlTrainingRunRepository extends JpaRepository<MlTrainingRun, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from MlTrainingRun run where run.id=:id")
    Optional<MlTrainingRun> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from MlTrainingRun run where run.status=:status order by run.createdAt, run.id")
    List<MlTrainingRun> nextQueued(@Param("status") MlTrainingRunStatus status,
            Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from MlTrainingRun run where run.status=:status and run.leaseExpiresAt < :now order by run.createdAt, run.id")
    List<MlTrainingRun> stale(@Param("status") MlTrainingRunStatus status,
            @Param("now") Instant now, Pageable page);
    List<MlTrainingRun> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
    List<MlTrainingRun> findByStatusOrderByCreatedAtAsc(MlTrainingRunStatus status);
    List<MlTrainingRun> findByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(MlTrainingRunStatus status, Instant leaseExpiresAt);
    long countByDatasetVersionId(UUID datasetVersionId);
    long countByFeatureSnapshotId(UUID featureSnapshotId);
}
