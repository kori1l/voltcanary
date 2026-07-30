package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.PredictionResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PredictionResultRepository extends JpaRepository<PredictionResult, UUID> {

    List<PredictionResult> findTop100ByDeviceIdOrderByCreatedAtDesc(UUID deviceId);

    Optional<PredictionResult> findFirstByDeviceIdOrderByCreatedAtDesc(UUID deviceId);

    Optional<PredictionResult> findFirstByDeviceIdAndShadowPredictionFalseOrderByCreatedAtDesc(UUID deviceId);
}
