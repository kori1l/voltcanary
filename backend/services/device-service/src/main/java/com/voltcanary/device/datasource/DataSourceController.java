package com.voltcanary.device.datasource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices/{deviceId}/data-sources")
public class DataSourceController {

    @PostMapping
    public Map<String, String> create(@PathVariable UUID deviceId, @RequestBody Map<String, Object> request) {
        return Map.of("message", "create data source endpoint is not implemented yet");
    }

    @GetMapping
    public List<Map<String, String>> list(@PathVariable UUID deviceId) {
        return List.of();
    }
}
