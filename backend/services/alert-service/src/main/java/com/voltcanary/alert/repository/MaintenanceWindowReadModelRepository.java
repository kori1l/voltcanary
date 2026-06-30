package com.voltcanary.alert.repository;

import com.voltcanary.alert.domain.MaintenanceWindowReadModel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceWindowReadModelRepository extends JpaRepository<MaintenanceWindowReadModel, UUID> {

    @Query("""
            select window
            from MaintenanceWindowReadModel window
            where window.deviceId = :deviceId
              and window.cancelledAt is null
              and window.startAt < :to
              and window.endAt > :from
            order by window.startAt asc
            """)
    List<MaintenanceWindowReadModel> findActiveOverlapping(
            @Param("deviceId") UUID deviceId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
