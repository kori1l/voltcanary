package com.voltcanary.device.devicetype;

import com.voltcanary.device.devicetype.DeviceTypeRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/device-types")
public class DeviceTypeController {

    private final DeviceTypeRepository deviceTypeRepository;

    public DeviceTypeController(DeviceTypeRepository deviceTypeRepository) {
        this.deviceTypeRepository = deviceTypeRepository;
    }

    @GetMapping
    public List<Map<String, String>> list() {
        return deviceTypeRepository.findAll().stream()
                .map(type -> Map.of("code", type.getCode(), "displayName", type.getDisplayName()))
                .toList();
    }
}
