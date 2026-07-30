package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlDeploymentAuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlDeploymentAuditEventRepository extends JpaRepository<MlDeploymentAuditEvent, UUID> {
    List<MlDeploymentAuditEvent> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
