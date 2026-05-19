package com.voltcanary.device.datasource;

import com.voltcanary.device.security.JwtService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices/{deviceId}/data-sources")
public class DataSourceController {

    private final DataSourceService dataSourceService;
    private final JwtService jwtService;

    public DataSourceController(DataSourceService dataSourceService, JwtService jwtService) {
        this.dataSourceService = dataSourceService;
        this.jwtService = jwtService;
    }

    @PostMapping
    public DataSourceResponse create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody DataSourceRequest request) {
        return dataSourceService.create(jwtService.parseBearerToken(authorization), deviceId, request);
    }

    @PostMapping("/{dataSourceId}/token")
    public SourceTokenResponse rotateToken(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("dataSourceId") UUID dataSourceId) {
        return dataSourceService.rotateToken(jwtService.parseBearerToken(authorization), deviceId, dataSourceId);
    }

    @PostMapping("/{dataSourceId}/token/revoke")
    public void revokeToken(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("dataSourceId") UUID dataSourceId) {
        dataSourceService.revokeToken(jwtService.parseBearerToken(authorization), deviceId, dataSourceId);
    }

    @PatchMapping("/{dataSourceId}")
    public DataSourceResponse update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("dataSourceId") UUID dataSourceId,
            @Valid @RequestBody DataSourceRequest request) {
        return dataSourceService.update(jwtService.parseBearerToken(authorization), deviceId, dataSourceId, request);
    }
}
