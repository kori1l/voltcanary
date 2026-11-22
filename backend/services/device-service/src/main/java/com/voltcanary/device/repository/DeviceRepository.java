package com.voltcanary.device.repository;

import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    Optional<Device> findByIdAndAccountId(UUID id, UUID accountId);

    @org.springframework.data.jpa.repository.Lock(
        jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
    )
    @Query("select device from Device device where device.id=:id and device.accountId=:accountId")
    Optional<Device> lockOwned(@Param("id") UUID id, @Param("accountId") UUID accountId);

    long countByStatus(DeviceStatus status);

    long countByDeviceTypeId(UUID deviceTypeId);

    @Query(
        value = """
        select count(*) from source_variable_bindings binding
        join data_sources source on source.id=binding.data_source_id
        where source.device_id=:deviceId and binding.enabled=true
        """,
        nativeQuery = true
    )
    long countEnabledVariableBindings(@Param("deviceId") UUID deviceId);

    @Query(
        """
        select count(device) > 0
        from Device device, Account account
        where device.id = :id
          and device.accountId = account.id
          and account.userId = :userId
          and account.status = com.voltcanary.device.domain.AccountStatus.ACTIVE
        """
    )
    boolean existsByIdAndAccountUserId(@Param("id") UUID id, @Param("userId") UUID userId);
}
