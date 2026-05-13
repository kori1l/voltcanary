package com.voltcanary.alert.repository;

import com.voltcanary.alert.notification.NotificationChannel;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {
}
