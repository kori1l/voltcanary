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

    long deleteByStatusAndResolvedAtBefore(AlertStatus status, Instant resolvedAtBefore);
}
