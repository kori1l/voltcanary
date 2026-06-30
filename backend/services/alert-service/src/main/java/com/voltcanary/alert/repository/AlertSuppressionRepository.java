package com.voltcanary.alert.repository;

import com.voltcanary.alert.domain.AlertSuppression;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertSuppressionRepository extends JpaRepository<AlertSuppression, UUID> {

    boolean existsBySourceEventId(UUID sourceEventId);

    boolean existsByAlertIdAndMaintenanceWindowId(UUID alertId, UUID maintenanceWindowId);
}
