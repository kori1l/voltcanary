package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.MlLabelAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MlLabelAuditRepository extends JpaRepository<MlLabelAudit, UUID> {
    List<MlLabelAudit> findByLabelIdOrderByEditedAtDesc(UUID labelId);
}
