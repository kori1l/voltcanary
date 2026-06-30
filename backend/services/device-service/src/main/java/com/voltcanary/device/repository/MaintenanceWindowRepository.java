package com.voltcanary.device.repository;

import com.voltcanary.device.domain.MaintenanceWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, UUID>, JpaSpecificationExecutor<MaintenanceWindow> {

    Optional<MaintenanceWindow> findByIdAndDeviceIdAndAccountId(UUID id, UUID deviceId, UUID accountId);

    List<MaintenanceWindow> findByOperationIdOrderByStartAtAsc(UUID operationId);

    @Query("""
            select window
            from MaintenanceWindow window
            where window.updatedAt > :updatedAfter
            order by window.updatedAt asc
            """)
    List<MaintenanceWindow> findChangesAfter(@Param("updatedAfter") Instant updatedAfter, Pageable pageable);
}
