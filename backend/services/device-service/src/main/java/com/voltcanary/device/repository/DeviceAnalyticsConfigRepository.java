package com.voltcanary.device.repository;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceAnalyticsConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface DeviceAnalyticsConfigRepository extends JpaRepository<DeviceAnalyticsConfig, UUID> {

    Optional<DeviceAnalyticsConfig> findByDeviceId(UUID deviceId);
}
