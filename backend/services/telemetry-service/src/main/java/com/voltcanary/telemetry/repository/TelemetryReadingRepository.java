package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.TelemetryReading;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelemetryReadingRepository extends JpaRepository<TelemetryReading, UUID> {

    List<TelemetryReading> findTop100ByDeviceIdOrderByRecordedAtDesc(UUID deviceId);

    Optional<TelemetryReading> findFirstByDeviceIdOrderByRecordedAtDesc(UUID deviceId);
}
