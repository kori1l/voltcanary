package com.voltcanary.alert.notification.repository;

import com.voltcanary.alert.notification.domain.NotificationChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {

    List<NotificationChannel> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    List<NotificationChannel> findByAccountIdAndEnabledTrueOrderByCreatedAtDesc(UUID accountId);

    Optional<NotificationChannel> findByIdAndAccountId(UUID id, UUID accountId);
}
