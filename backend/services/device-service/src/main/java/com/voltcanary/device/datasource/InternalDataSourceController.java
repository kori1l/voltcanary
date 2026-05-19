package com.voltcanary.device.datasource;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalDataSourceController {

    private final DataSourceService dataSourceService;

    public InternalDataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @GetMapping("/internal/devices/{deviceId}/data-sources/{sourceId}/exists")
    public Map<String, Boolean> exists(
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("sourceId") String sourceId) {
        return Map.of("exists", dataSourceService.exists(deviceId, sourceId));
    }

    @GetMapping("/internal/data-sources/by-token-prefix/{tokenPrefix}")
    public InternalDataSourceResponse byTokenPrefix(@PathVariable("tokenPrefix") String tokenPrefix) {
        return dataSourceService.findInternalByTokenPrefix(tokenPrefix);
    }

    @GetMapping("/internal/data-sources/by-external-id/{externalId}")
    public InternalDataSourceResponse byExternalId(@PathVariable("externalId") String externalId) {
        return dataSourceService.findInternalByExternalId(externalId);
    }

    @GetMapping("/internal/data-sources/{dataSourceId}")
    public InternalDataSourceResponse byId(@PathVariable("dataSourceId") UUID dataSourceId) {
        return dataSourceService.findInternal(dataSourceId);
    }

    @PostMapping("/internal/data-sources/{dataSourceId}/token-used")
    public void markTokenUsed(@PathVariable("dataSourceId") UUID dataSourceId) {
        dataSourceService.markTokenUsed(dataSourceId);
    }
}
