package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MaintenanceOutcomeReadModel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceOutcomeReadModelRepository extends JpaRepository<MaintenanceOutcomeReadModel, UUID> {

    @Query("""
            select outcome
            from MaintenanceOutcomeReadModel outcome
            where outcome.deviceId = :deviceId
              and outcome.observedAt >= :from
              and outcome.observedAt < :to
            order by outcome.observedAt asc
            """)
    List<MaintenanceOutcomeReadModel> findByDeviceAndObservedRange(
            @Param("deviceId") UUID deviceId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select outcome
            from MaintenanceOutcomeReadModel outcome
            where outcome.accountId = :accountId
              and outcome.observedAt >= :from
              and outcome.observedAt <= :to
            order by outcome.deviceId asc, outcome.observedAt asc
            """)
    List<MaintenanceOutcomeReadModel> findByAccountAndObservedRange(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
