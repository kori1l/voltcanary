package com.voltcanary.device.controller;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.repository.DataSourceRepository;
import com.voltcanary.device.repository.DeviceRepository;
import com.voltcanary.device.repository.DeviceTypeRepository;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import com.voltcanary.common.web.VehiclePositionTargets;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InternalPositionSourceController {
    private final InternalServiceAuth auth;
    private final DataSourceRepository sources;
    private final DeviceRepository devices;
    private final DeviceTypeRepository types;

    public InternalPositionSourceController(InternalServiceAuth auth, DataSourceRepository sources,
            DeviceRepository devices, DeviceTypeRepository types) {
        this.auth = auth;
        this.sources = sources;
        this.devices = devices;
        this.types = types;
    }

    @GetMapping("/internal/devices/{deviceId}/position-source")
    public Map<String, String> source(@RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
            @PathVariable("deviceId") UUID deviceId, @RequestParam("sourceId") String sourceId) {
        auth.require(token);
        var device = devices.findById(deviceId).orElseThrow(() -> missing());
        var source = sources.findByDeviceId(deviceId).stream().filter(item -> sourceId.equals(item.getSourceId()))
                .findFirst().orElseThrow(() -> missing());
        var type = types.findById(device.getDeviceTypeId()).orElseThrow(() -> missing());
        return Map.of("deviceId", deviceId.toString(), "dataSourceId", source.getId().toString(),
                "sourceId", source.getSourceId(), "deviceType", type.getCode(),
                "sourceType", source.getSourceType().name(), "protocol", source.getProtocol().name());
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Device source not found");
    }

    @PostMapping("/internal/vehicles/position-sources")
    public Map<String, Boolean> fleet(@RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
            @RequestBody VehiclePositionTargets request) {
        auth.require(token);
        if (request.userId() == null || request.targets() == null || request.targets().isEmpty() || request.targets().size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fleet requires a user and 1–100 targets");
        }
        for (VehiclePositionTargets.Target target : request.targets()) {
            if (target == null || target.deviceId() == null || target.sourceId() == null || target.sourceId().isBlank()
                    || target.sourceId().length() > 255 || !devices.existsByIdAndAccountUserId(target.deviceId(), request.userId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vehicle access denied");
            }
            Map<String, String> binding = source(token, target.deviceId(), target.sourceId());
            if (!"VEHICLE".equals(binding.get("deviceType"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fleet targets must be vehicles");
            }
        }
        return Map.of("allowed", true);
    }
}
