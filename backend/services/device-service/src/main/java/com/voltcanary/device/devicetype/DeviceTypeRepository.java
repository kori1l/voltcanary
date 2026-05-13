package com.voltcanary.device.devicetype;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTypeRepository extends JpaRepository<DeviceType, UUID> {

    Optional<DeviceType> findByCode(String code);
}
