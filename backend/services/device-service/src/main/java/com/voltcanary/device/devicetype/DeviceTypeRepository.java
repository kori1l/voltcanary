package com.voltcanary.device.devicetype;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTypeRepository extends JpaRepository<DeviceType, UUID> {
}
