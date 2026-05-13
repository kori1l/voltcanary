package com.voltcanary.device.datasource;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/devices/{deviceId}/data-sources")
public class InternalDataSourceController {

    private final DataSourceService dataSourceService;

    public InternalDataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @GetMapping("/{sourceId}/exists")
    public Map<String, Boolean> exists(
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("sourceId") String sourceId) {
        return Map.of("exists", dataSourceService.exists(deviceId, sourceId));
    }
}
