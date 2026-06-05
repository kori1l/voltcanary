package com.voltcanary.device.repository;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.Sensor;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface SensorRepository extends JpaRepository<Sensor, UUID> {

    List<Sensor> findByDeviceId(UUID deviceId);
}
