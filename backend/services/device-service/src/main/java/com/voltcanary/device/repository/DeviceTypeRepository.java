package com.voltcanary.device.repository;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;



public interface DeviceTypeRepository extends JpaRepository<DeviceType, UUID> {

    Optional<DeviceType> findByCode(String code);
}
