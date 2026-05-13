package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.risk.RiskScore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskScoreRepository extends JpaRepository<RiskScore, UUID> {

    Optional<RiskScore> findFirstByDeviceIdOrderByCalculatedAtDesc(UUID deviceId);
}
