package com.voltcanary.alert.repository;

import com.voltcanary.alert.domain.AlertEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    List<AlertEvent> findByAlertIdOrderByCreatedAtDesc(UUID alertId);

    boolean existsByAlertIdAndEventType(UUID alertId, String eventType);

    long deleteByCreatedAtBefore(java.time.Instant createdAtBefore);
}
