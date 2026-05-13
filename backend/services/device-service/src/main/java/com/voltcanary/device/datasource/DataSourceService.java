package com.voltcanary.device.datasource;

import com.voltcanary.device.device.Device;
import com.voltcanary.device.device.DeviceService;
import com.voltcanary.device.security.AuthenticatedUser;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataSourceService {

    private final DeviceService deviceService;
    private final DataSourceRepository dataSourceRepository;

    public DataSourceService(DeviceService deviceService, DataSourceRepository dataSourceRepository) {
        this.deviceService = deviceService;
        this.dataSourceRepository = dataSourceRepository;
    }

    @Transactional
    public DataSourceResponse create(AuthenticatedUser user, UUID deviceId, DataSourceRequest request) {
        Device device = deviceService.findOwned(user, deviceId);

        DataSource dataSource = new DataSource();
        dataSource.setId(UUID.randomUUID());
        dataSource.setDeviceId(device.getId());
        dataSource.setName(request.externalId());
        dataSource.setType(request.sourceType());
        dataSource.setConnectionRef(request.protocol());
        dataSource.setCreatedAt(Instant.now());

        return toResponse(dataSourceRepository.save(dataSource));
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID deviceId, String sourceId) {
        return dataSourceRepository.existsByDeviceIdAndName(deviceId, sourceId);
    }

    private DataSourceResponse toResponse(DataSource dataSource) {
        return new DataSourceResponse(
                dataSource.getId(),
                dataSource.getDeviceId(),
                dataSource.getType(),
                dataSource.getConnectionRef(),
                dataSource.getName());
    }
}
