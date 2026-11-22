package com.voltcanary.device.service;

import com.voltcanary.common.web.VehiclePositionTargets;
import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.repository.DataSourceRepository;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.DeviceTypeRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class PositionSourceReads {

    private final InternalServiceAuth auth;
    private final DataSourceRepository sources;
    private final DeviceRepository devices;
    private final DeviceTypeRepository types;

    public Map<String, String> source(String token, UUID deviceId, String sourceId) {
        auth.require(token);
        var device = devices.findById(deviceId).orElseThrow(() -> missing());
        var source = sources
            .findByDeviceId(deviceId)
            .stream()
            .filter(item -> sourceId.equals(item.getSourceId()))
            .findFirst()
            .orElseThrow(() -> missing());
        var type = types.findById(device.getDeviceTypeId()).orElseThrow(() -> missing());
        return Map.of(
            "deviceId",
            deviceId.toString(),
            "dataSourceId",
            source.getId().toString(),
            "sourceId",
            source.getSourceId(),
            "deviceType",
            type.getCode(),
            "sourceType",
            source.getSourceType().name(),
            "protocol",
            source.getProtocol().name()
        );
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Device source not found");
    }

    public Map<String, Boolean> fleet(String token, VehiclePositionTargets request) {
        auth.require(token);
        if (
            request.userId() == null ||
            request.targets() == null ||
            request.targets().isEmpty() ||
            request.targets().size() > 100
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Fleet requires a user and 1вЂ“100 targets"
            );
        }
        for (VehiclePositionTargets.Target target : request.targets()) {
            if (
                target == null ||
                target.deviceId() == null ||
                target.sourceId() == null ||
                target.sourceId().isBlank() ||
                target.sourceId().length() > 255 ||
                !devices.existsByIdAndAccountUserId(target.deviceId(), request.userId())
            ) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vehicle access denied");
            }
            Map<String, String> binding = source(token, target.deviceId(), target.sourceId());
            if (!"VEHICLE".equals(binding.get("deviceType"))) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Fleet targets must be vehicles"
                );
            }
        }
        return Map.of("allowed", true);
    }
}
