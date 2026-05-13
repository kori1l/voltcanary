package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.prediction.PredictionResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PredictionResultRepository extends JpaRepository<PredictionResult, UUID> {

    List<PredictionResult> findTop100ByDeviceIdOrderByCreatedAtDesc(UUID deviceId);

    Optional<PredictionResult> findFirstByDeviceIdOrderByCreatedAtDesc(UUID deviceId);
}
