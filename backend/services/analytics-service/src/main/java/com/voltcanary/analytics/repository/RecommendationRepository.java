package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.recommendation.Recommendation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, UUID> {

    List<Recommendation> findTop100ByDeviceIdOrderByCreatedAtDesc(UUID deviceId);
}
