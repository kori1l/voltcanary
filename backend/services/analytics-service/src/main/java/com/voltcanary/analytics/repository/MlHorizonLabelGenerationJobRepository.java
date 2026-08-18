package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlHorizonLabelGenerationJob;
import com.voltcanary.analytics.domain.MlHorizonLabelGenerationStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlHorizonLabelGenerationJobRepository extends JpaRepository<MlHorizonLabelGenerationJob, UUID> {
    Optional<MlHorizonLabelGenerationJob> findByIdAndAccountId(UUID id, UUID accountId);
    Optional<MlHorizonLabelGenerationJob> findFirstByAccountIdAndIdempotencyKeyAndStatusInOrderBySubmittedAtDesc(
            UUID accountId,
            String idempotencyKey,
            Collection<MlHorizonLabelGenerationStatus> statuses);
}
