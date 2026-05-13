package com.voltcanary.device.analyticsconfig;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceAnalyticsConfigRepository extends JpaRepository<DeviceAnalyticsConfig, UUID> {

    Optional<DeviceAnalyticsConfig> findByDeviceId(UUID deviceId);
}
