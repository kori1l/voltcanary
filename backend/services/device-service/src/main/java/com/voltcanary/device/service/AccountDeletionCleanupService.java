package com.voltcanary.device.service;

import com.voltcanary.device.domain.DataSource;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import com.voltcanary.device.domain.SourceStatus;
import com.voltcanary.device.event.DeviceRegistryEventPublisher;
import com.voltcanary.device.repository.DataSourceRepository;
import com.voltcanary.device.repository.DeviceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountDeletionCleanupService {

    private final DeviceRepository deviceRepository;
    private final DataSourceRepository dataSourceRepository;
    private final DeviceRegistryEventPublisher eventPublisher;

    public AccountDeletionCleanupService(
            DeviceRepository deviceRepository,
            DataSourceRepository dataSourceRepository,
            DeviceRegistryEventPublisher eventPublisher) {
        this.deviceRepository = deviceRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.eventPublisher = eventPublisher;
    }

    public void cleanupDeletedAccount(UUID accountId, Instant cleanupTime) {
        List<Device> devices = deviceRepository.findByAccountIdOrderByCreatedAtAsc(accountId);
        if (devices.isEmpty()) {
            return;
        }

        List<UUID> deviceIds = devices.stream().map(Device::getId).toList();
        List<DataSource> dataSources = dataSourceRepository.findByDeviceIdIn(deviceIds).stream()
                .filter(dataSource -> revokeDataSource(accountId, dataSource, cleanupTime))
                .toList();
        dataSourceRepository.saveAll(dataSources);

        List<Device> changedDevices = devices.stream()
                .filter(device -> decommissionDevice(device, cleanupTime))
                .toList();
        deviceRepository.saveAll(changedDevices);
    }

    private boolean revokeDataSource(UUID accountId, DataSource dataSource, Instant cleanupTime) {
        boolean changed = dataSource.isEnabled()
                || dataSource.getStatus() != SourceStatus.REVOKED
                || dataSource.getTokenRevokedAt() == null;
        if (!changed) {
            return false;
        }
        dataSource.setEnabled(false);
        dataSource.setStatus(SourceStatus.REVOKED);
        if (dataSource.getTokenRevokedAt() == null) {
            dataSource.setTokenRevokedAt(cleanupTime);
        }
        dataSource.setUpdatedAt(cleanupTime);
        eventPublisher.publish(
                "DATA_SOURCE",
                "ACCOUNT_DELETED_REVOKED",
                accountId,
                dataSource.getDeviceId(),
                dataSource.getId(),
                dataSource.getStatus().name());
        return true;
    }

    private boolean decommissionDevice(Device device, Instant cleanupTime) {
        if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            return false;
        }
        device.setStatus(DeviceStatus.DECOMMISSIONED);
        device.setUpdatedAt(cleanupTime);
        eventPublisher.publish(
                "DEVICE",
                "ACCOUNT_DELETED_DECOMMISSIONED",
                device.getAccountId(),
                device.getId(),
                device.getId(),
                device.getStatus().name());
        return true;
    }
}
