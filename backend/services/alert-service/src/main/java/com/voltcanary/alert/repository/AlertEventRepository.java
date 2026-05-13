package com.voltcanary.alert.repository;

import com.voltcanary.alert.alert.AlertEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    List<AlertEvent> findByAlertIdOrderByCreatedAtDesc(UUID alertId);
}
