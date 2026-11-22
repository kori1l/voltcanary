package com.voltcanary.device.repository;

import com.voltcanary.device.domain.MaintenanceOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceOutcomeRepository extends JpaRepository<MaintenanceOutcome, UUID> {

    Optional<MaintenanceOutcome> findByIdAndAccountId(UUID id, UUID accountId);

    List<MaintenanceOutcome> findByAccountIdAndOperationIdOrderByObservedAtAsc(UUID accountId, UUID operationId);

    @Query("""
            select outcome
            from MaintenanceOutcome outcome
            where outcome.updatedAt > :updatedAfter
               or (outcome.updatedAt = :updatedAfter and :afterId is not null and outcome.id > :afterId)
            order by outcome.updatedAt asc, outcome.id asc
            """)
    List<MaintenanceOutcome> findChangesAfter(@Param("updatedAfter") Instant updatedAfter, @Param("afterId") UUID afterId, Pageable pageable);
}
