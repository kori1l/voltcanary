package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.anomaly.Anomaly;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnomalyRepository extends JpaRepository<Anomaly, UUID> {

    List<Anomaly> findTop100ByDeviceIdOrderByDetectedAtDesc(UUID deviceId);

    void deleteByDeviceId(UUID deviceId);
}
