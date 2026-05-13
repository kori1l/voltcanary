package com.voltcanary.device.devicetype;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/device-types")
public class DeviceTypeController {

    @GetMapping
    public List<Map<String, String>> list() {
        return List.of();
    }
}
