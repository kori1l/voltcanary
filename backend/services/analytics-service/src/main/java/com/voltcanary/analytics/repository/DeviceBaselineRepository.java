package com.voltcanary.analytics.repository;

import com.voltcanary.analytics.domain.DeviceBaseline;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceBaselineRepository extends JpaRepository<DeviceBaseline, UUID> {

    Optional<DeviceBaseline> findFirstByDeviceIdOrderByCalculatedAtDesc(UUID deviceId);
}
