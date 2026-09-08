package com.voltcanary.alert.notification.repository;

import com.voltcanary.alert.notification.domain.NotificationPreference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    List<NotificationPreference> findByAccountIdAndEnabledTrueOrderByCreatedAtAsc(UUID accountId);

    Optional<NotificationPreference> findByAccountIdAndUserIdIsNull(UUID accountId);
}
