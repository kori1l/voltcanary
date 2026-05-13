package com.voltcanary.device.device;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    Optional<Device> findByIdAndAccountId(UUID id, UUID accountId);
}
