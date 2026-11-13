package com.voltcanary.device.repository;

import com.voltcanary.device.domain.DataSource;
import com.voltcanary.device.domain.DataSourceProtocol;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.SourceStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<DataSource> findByIdAndDeviceId(UUID id, UUID deviceId);

    List<DataSource> findByDeviceId(UUID deviceId);

    List<DataSource> findByDeviceIdIn(List<UUID> deviceIds);

    long countByProtocol(DataSourceProtocol protocol);

    long countByStatus(SourceStatus status);

    Optional<DataSource> findByTokenPrefix(String tokenPrefix);

    Optional<DataSource> findFirstBySourceId(String sourceId);
}
