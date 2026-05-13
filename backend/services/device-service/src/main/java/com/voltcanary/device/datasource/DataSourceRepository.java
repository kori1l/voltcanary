package com.voltcanary.device.datasource;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {

    List<DataSource> findByDeviceId(UUID deviceId);

    boolean existsByDeviceIdAndName(UUID deviceId, String name);
}
