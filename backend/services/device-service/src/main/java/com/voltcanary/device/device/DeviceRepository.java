package com.voltcanary.device.device;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    Optional<Device> findByIdAndAccountId(UUID id, UUID accountId);

    long countByStatus(DeviceStatus status);

    long countByDeviceTypeId(UUID deviceTypeId);

    @Query("""
            select count(device) > 0
            from Device device, Account account
            where device.id = :id
              and device.accountId = account.id
              and account.userId = :userId
            """)
    boolean existsByIdAndAccountUserId(@Param("id") UUID id, @Param("userId") UUID userId);
}
