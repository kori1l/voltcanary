package com.voltcanary.device.repository;

import com.voltcanary.device.domain.MaintenanceWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceWindowRepository
    extends JpaRepository<MaintenanceWindow, UUID>, JpaSpecificationExecutor<MaintenanceWindow>
{
    Optional<MaintenanceWindow> findByIdAndDeviceIdAndAccountId(
        UUID id,
        UUID deviceId,
        UUID accountId
    );

    List<MaintenanceWindow> findByOperationIdInOrderByStartAtAsc(
        java.util.Collection<UUID> operationIds
    );

    List<MaintenanceWindow> findByOperationIdOrderByStartAtAsc(UUID operationId);

    @Query(
        """
        select window
        from MaintenanceWindow window
        where window.updatedAt > :updatedAfter
           or (window.updatedAt = :updatedAfter and :afterId is not null and window.id > :afterId)
        order by window.updatedAt asc, window.id asc
        """
    )
    List<MaintenanceWindow> findChangesAfter(
        @Param("updatedAfter") Instant updatedAfter,
        @Param("afterId") UUID afterId,
        Pageable pageable
    );
}
