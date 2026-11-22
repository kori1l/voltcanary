package com.voltcanary.device.controller;

import com.voltcanary.device.service.DeviceTypeCatalog;
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

    private final DeviceTypeCatalog catalog;

    @GetMapping
    public List<Map<String, String>> list() {
        return catalog.list();
    }
}
