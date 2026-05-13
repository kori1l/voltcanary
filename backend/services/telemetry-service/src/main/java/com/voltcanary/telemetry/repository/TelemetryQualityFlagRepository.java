package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.TelemetryQualityFlag;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelemetryQualityFlagRepository extends JpaRepository<TelemetryQualityFlag, UUID> {

    List<TelemetryQualityFlag> findByDeviceIdOrderByCreatedAtDesc(UUID deviceId);
}
