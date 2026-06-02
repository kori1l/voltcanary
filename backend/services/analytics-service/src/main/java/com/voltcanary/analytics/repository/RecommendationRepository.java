package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.recommendation.Recommendation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationRepository extends JpaRepository<Recommendation, UUID> {

    List<Recommendation> findTop100ByDeviceIdOrderByCreatedAtDesc(UUID deviceId);

    List<Recommendation> findTop100ByDeviceIdAndStatusOrderByCreatedAtDesc(UUID deviceId, String status);

    void deleteByDeviceId(UUID deviceId);

    @Modifying
    @Query("""
            update Recommendation recommendation
            set recommendation.status = 'SUPERSEDED',
                recommendation.supersededAt = :supersededAt
            where recommendation.deviceId = :deviceId
              and recommendation.status = 'ACTIVE'
            """)
    int supersedeActiveByDeviceId(
            @Param("deviceId") UUID deviceId,
            @Param("supersededAt") Instant supersededAt);
}
