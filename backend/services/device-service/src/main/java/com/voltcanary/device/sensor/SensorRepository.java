package com.voltcanary.device.sensor;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorRepository extends JpaRepository<Sensor, UUID> {
}
