package com.voltcanary.alert.repository;

import com.voltcanary.alert.domain.NotificationAttempt;
import com.voltcanary.alert.domain.NotificationAttemptStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {

    List<NotificationAttempt> findByAlertIdOrderByCreatedAtDesc(UUID alertId);

    long countByStatus(NotificationAttemptStatus status);

    long deleteByCreatedAtBefore(java.time.Instant createdAtBefore);
}
