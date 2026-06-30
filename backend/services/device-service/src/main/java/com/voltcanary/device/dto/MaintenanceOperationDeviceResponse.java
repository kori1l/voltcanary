package com.voltcanary.device.dto;

import java.util.UUID;

public record MaintenanceOperationDeviceResponse(
        UUID deviceId,
        String deviceName,
        String deviceType,
        UUID locationId,
        String locationName,
        UUID windowId,
        String status) {
}
