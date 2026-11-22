package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlHorizonLabelGenerationJob;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlHorizonLabelGenerationJobRepository
    extends JpaRepository<MlHorizonLabelGenerationJob, UUID>
{
    @org.springframework.data.jpa.repository.Query(
        value = "select * from ml_horizon_label_generation_jobs where id=:id for update skip locked",
        nativeQuery = true
    )
    Optional<MlHorizonLabelGenerationJob> tryLockById(
        @org.springframework.data.repository.query.Param("id") UUID id
    );

    java.util.List<MlHorizonLabelGenerationJob> findByStatusInOrderBySubmittedAtAsc(
        Collection<MlHorizonLabelGenerationStatus> statuses,
        org.springframework.data.domain.Pageable page
    );

    Optional<MlHorizonLabelGenerationJob> findByIdAndAccountId(UUID id, UUID accountId);
    Optional<MlHorizonLabelGenerationJob> findFirstByAccountIdAndIdempotencyKeyAndStatusInOrderBySubmittedAtDesc(
        UUID accountId,
        String idempotencyKey,
        Collection<MlHorizonLabelGenerationStatus> statuses
    );
}
