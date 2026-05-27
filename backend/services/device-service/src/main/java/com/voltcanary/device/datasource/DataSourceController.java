package com.voltcanary.device.datasource;

import com.voltcanary.device.security.AuthenticatedUserResolver;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final AuthenticatedUserResolver userResolver;

    public DataSourceController(DataSourceService dataSourceService, AuthenticatedUserResolver userResolver) {
        this.dataSourceService = dataSourceService;
        this.userResolver = userResolver;
    }

    @GetMapping
    public List<DataSourceResponse> list(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId) {
        return dataSourceService.list(userResolver.resolve(headers), deviceId);
    }

    @GetMapping("/{dataSourceId}")
    public DataSourceResponse get(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("dataSourceId") UUID dataSourceId) {
        return dataSourceService.get(userResolver.resolve(headers), deviceId, dataSourceId);
    }

    @PostMapping
    public DataSourceResponse create(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody DataSourceRequest request) {
        return dataSourceService.create(userResolver.resolve(headers), deviceId, request);
    }

    @PostMapping(value = "/imports/csv", consumes = MediaType.TEXT_PLAIN_VALUE)
    public DataSourceImportResponse importCsv(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @RequestBody String csv) {
        return dataSourceService.importCsv(userResolver.resolve(headers), deviceId, csv);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public String exportCsv(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId) {
        return dataSourceService.exportCsv(userResolver.resolve(headers), deviceId);
    }

    @PostMapping("/{dataSourceId}/token")
    public SourceTokenResponse rotateToken(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("dataSourceId") UUID dataSourceId) {
        return dataSourceService.rotateToken(userResolver.resolve(headers), deviceId, dataSourceId);
    }

    @PostMapping("/{dataSourceId}/token/revoke")
    public void revokeToken(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("dataSourceId") UUID dataSourceId) {
        dataSourceService.revokeToken(userResolver.resolve(headers), deviceId, dataSourceId);
    }

    @PatchMapping("/{dataSourceId}")
    public DataSourceResponse update(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("dataSourceId") UUID dataSourceId,
            @Valid @RequestBody DataSourceRequest request) {
        return dataSourceService.update(userResolver.resolve(headers), deviceId, dataSourceId, request);
    }

    @DeleteMapping("/{dataSourceId}")
    public void disable(
            @RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("dataSourceId") UUID dataSourceId) {
        dataSourceService.disable(userResolver.resolve(headers), deviceId, dataSourceId);
    }
}
