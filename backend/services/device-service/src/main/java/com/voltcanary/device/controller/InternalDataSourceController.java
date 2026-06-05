package com.voltcanary.device.controller;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.dto.InternalDataSourceResponse;
import com.voltcanary.device.metrics.DeviceOperationalMetrics;
import com.voltcanary.device.service.DataSourceService;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class InternalDataSourceController {

    private final DataSourceService dataSourceService;
    private final InternalServiceAuth internalServiceAuth;
    private final DeviceOperationalMetrics metrics;

    public InternalDataSourceController(
            DataSourceService dataSourceService,
            InternalServiceAuth internalServiceAuth,
            DeviceOperationalMetrics metrics) {
        this.dataSourceService = dataSourceService;
        this.internalServiceAuth = internalServiceAuth;
        this.metrics = metrics;
    }

    @GetMapping("/internal/devices/{deviceId}/data-sources/{sourceId}/exists")
    public Map<String, Boolean> exists(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @PathVariable("deviceId") UUID deviceId,
            @PathVariable("sourceId") String sourceId) {
        internalServiceAuth.require(internalToken);
        Timer.Sample sample = metrics.startInternalValidation();
        try {
            boolean exists = dataSourceService.exists(deviceId, sourceId);
            metrics.stopInternalValidation(sample, exists ? "allowed" : "rejected");
            return Map.of("exists", exists);
        } catch (RuntimeException exception) {
            metrics.stopInternalValidation(sample, "error");
            throw exception;
        }
    }

    @GetMapping("/internal/data-sources/by-token-prefix/{tokenPrefix}")
    public InternalDataSourceResponse byTokenPrefix(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @PathVariable("tokenPrefix") String tokenPrefix) {
        internalServiceAuth.require(internalToken);
        return dataSourceService.findInternalByTokenPrefix(tokenPrefix);
    }

    @GetMapping("/internal/data-sources/by-external-id/{externalId}")
    public InternalDataSourceResponse byExternalId(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @PathVariable("externalId") String externalId) {
        internalServiceAuth.require(internalToken);
        return dataSourceService.findInternalByExternalId(externalId);
    }

    @GetMapping("/internal/data-sources/{dataSourceId}")
    public InternalDataSourceResponse byId(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @PathVariable("dataSourceId") UUID dataSourceId) {
        internalServiceAuth.require(internalToken);
        return dataSourceService.findInternal(dataSourceId);
    }

    @PostMapping("/internal/data-sources/{dataSourceId}/token-used")
    public void markTokenUsed(
            @RequestHeader(value = InternalServiceAuth.HEADER, required = false) String internalToken,
            @PathVariable("dataSourceId") UUID dataSourceId) {
        internalServiceAuth.require(internalToken);
        dataSourceService.markTokenUsed(dataSourceId);
    }
}
