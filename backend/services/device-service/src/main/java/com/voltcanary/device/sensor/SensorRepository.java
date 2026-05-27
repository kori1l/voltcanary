package com.voltcanary.device.sensor;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorRepository extends JpaRepository<Sensor, UUID> {

    List<Sensor> findByDeviceId(UUID deviceId);
}
