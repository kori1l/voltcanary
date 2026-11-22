package com.voltcanary.device.service;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceType;
import com.voltcanary.device.domain.Location;
import com.voltcanary.device.domain.MaintenanceOperation;
import com.voltcanary.device.dto.MaintenanceOperationDeviceResponse;
import com.voltcanary.device.exception.NotFoundException;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.DeviceTypeRepository;
import com.voltcanary.device.repository.LocationRepository;
import com.voltcanary.device.repository.MaintenanceWindowRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaintenanceOperationViews {
    private final MaintenanceWindowRepository windows;
    private final DeviceRepository devices;
    private final DeviceTypeRepository types;
    private final LocationRepository locations;

    @Transactional(readOnly = true)
    public Map<UUID, List<MaintenanceOperationDeviceResponse>> devices(Collection<MaintenanceOperation> operations) {
        if (operations.isEmpty()) return Map.of();
        var allWindows = windows.findByOperationIdInOrderByStartAtAsc(operations.stream().map(MaintenanceOperation::getId).toList());
        var deviceMap = devices.findAllById(allWindows.stream().map(window -> window.getDeviceId()).distinct().toList()).stream()
                .collect(Collectors.toMap(Device::getId, Function.identity()));
        var typeMap = types.findAllById(deviceMap.values().stream().map(Device::getDeviceTypeId).distinct().toList()).stream()
                .collect(Collectors.toMap(DeviceType::getId, Function.identity()));
        var locationMap = locations.findAllById(deviceMap.values().stream().map(Device::getLocationId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Location::getId, Function.identity()));
        Map<UUID, List<MaintenanceOperationDeviceResponse>> result = new HashMap<>();
        Instant now = Instant.now();
        for (var window : allWindows) {
            var device = deviceMap.get(window.getDeviceId());
            if (device == null) throw new NotFoundException("Maintenance device not found");
            var type = typeMap.get(device.getDeviceTypeId());
            var location = locationMap.get(device.getLocationId());
            String status = window.getCancelledAt() != null ? "CANCELLED" : now.isBefore(window.getStartAt()) ? "SCHEDULED"
                    : now.isBefore(window.getEndAt()) ? "ACTIVE" : "FINISHED";
            var row = new MaintenanceOperationDeviceResponse(device.getId(), device.getName(), type == null ? null : type.getCode(),
                    device.getLocationId(), location == null ? null : location.getName(), window.getId(), status);
            result.computeIfAbsent(window.getOperationId(), key -> new ArrayList<>()).add(row);
        }
        return result;
    }
}
