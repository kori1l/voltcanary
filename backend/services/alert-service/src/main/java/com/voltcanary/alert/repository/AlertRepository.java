package com.voltcanary.alert.repository;

import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, UUID>, JpaSpecificationExecutor<Alert> {

    List<Alert> findTop100ByOrderByCreatedAtDesc();

    Optional<Alert> findFirstByDeduplicationKeyOrderByCreatedAtDesc(String deduplicationKey);

    Optional<Alert> findFirstBySourceEventId(UUID sourceEventId);

    List<Alert> findByStatusAndSeverityAndCreatedAtBeforeOrderByCreatedAtAsc(
            AlertStatus status,
            AlertSeverity severity,
            Instant createdAtBefore);

    Optional<Alert> findFirstByDeviceIdAndAlertTypeAndSeverityAndStatusNotAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID deviceId,
            String alertType,
            AlertSeverity severity,
            AlertStatus status,
            Instant createdAtFrom,
            Instant createdAtTo);

    @Query("""
            select alert
            from Alert alert
            where alert.deviceId = :deviceId
              and alert.status <> :resolvedStatus
              and (
                (alert.calculationWindowStart is not null
                  and alert.calculationWindowEnd is not null
                  and alert.calculationWindowStart < :to
                  and alert.calculationWindowEnd > :from)
                or (alert.calculationWindowStart is null
                  and alert.calculationWindowEnd is null
                  and alert.createdAt >= :from
                  and alert.createdAt < :to)
              )
            order by alert.createdAt asc
            """)
    List<Alert> findSuppressibleOverlapping(
            @Param("deviceId") UUID deviceId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("resolvedStatus") AlertStatus resolvedStatus);

    long deleteByStatusAndResolvedAtBefore(AlertStatus status, Instant resolvedAtBefore);
}
