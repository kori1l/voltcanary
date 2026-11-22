package com.voltcanary.device.controller;

import com.voltcanary.device.repository.DeviceTypeRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/device-types")
@RequiredArgsConstructor
public class DeviceTypeController {

    private final DeviceTypeRepository deviceTypeRepository;

    @GetMapping
    public List<Map<String, String>> list() {
        return deviceTypeRepository.findAll().stream()
                .map(type -> Map.of("code", type.getCode(), "displayName", type.getDisplayName()))
                .toList();
    }
}
