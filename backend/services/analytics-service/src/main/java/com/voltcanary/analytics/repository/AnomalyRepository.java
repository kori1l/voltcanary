package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.Anomaly;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnomalyRepository extends JpaRepository<Anomaly, UUID> {

    List<Anomaly> findTop100ByDeviceIdOrderByDetectedAtDesc(UUID deviceId);

    List<Anomaly> findTop100ByDeviceIdAndStatusOrderByDetectedAtDesc(UUID deviceId, String status);

    void deleteByDeviceId(UUID deviceId);

    @Modifying
    @Query("""
            update Anomaly anomaly
            set anomaly.status = 'SUPERSEDED',
                anomaly.supersededAt = :supersededAt
            where anomaly.deviceId = :deviceId
              and anomaly.status = 'ACTIVE'
            """)
    int supersedeActiveByDeviceId(
            @Param("deviceId") UUID deviceId,
            @Param("supersededAt") Instant supersededAt);
}
