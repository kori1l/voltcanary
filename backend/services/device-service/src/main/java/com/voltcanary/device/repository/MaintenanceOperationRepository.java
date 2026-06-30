package com.voltcanary.device.repository;

import com.voltcanary.device.domain.MaintenanceOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MaintenanceOperationRepository extends JpaRepository<MaintenanceOperation, UUID>, JpaSpecificationExecutor<MaintenanceOperation> {

    Optional<MaintenanceOperation> findByIdAndAccountId(UUID id, UUID accountId);
}
