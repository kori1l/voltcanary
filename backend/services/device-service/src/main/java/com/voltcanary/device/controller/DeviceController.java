package com.voltcanary.device.controller;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.DeviceStatus;
import com.voltcanary.device.dto.DeviceImportResponse;
import com.voltcanary.device.dto.DeviceRequest;
import com.voltcanary.device.dto.DeviceResponse;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.DeviceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final AuthenticatedUserResolver userResolver;

    public DeviceController(DeviceService deviceService, AuthenticatedUserResolver userResolver) {
        this.deviceService = deviceService;
        this.userResolver = userResolver;
    }

    @PostMapping
    public DeviceResponse create(
            @RequestHeader HttpHeaders headers,
            @Valid @RequestBody DeviceRequest request) {
        return deviceService.create(user(headers), request);
    }

    @PostMapping(value = "/imports/csv", consumes = MediaType.TEXT_PLAIN_VALUE)
    public DeviceImportResponse importCsv(
            @RequestHeader HttpHeaders headers,
            @RequestBody String csv) {
        return deviceService.importCsv(user(headers), csv);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public String exportCsv(@RequestHeader HttpHeaders headers) {
        return deviceService.exportCsv(user(headers));
    }

    @GetMapping
    public List<DeviceResponse> list(
            @RequestHeader HttpHeaders headers,
            @RequestParam(value = "locationId", required = false) UUID locationId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) DeviceStatus status,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        return deviceService.list(user(headers), locationId, type, status, search, limit, offset);
    }

    @GetMapping("/{id}")
    public DeviceResponse get(
            @RequestHeader HttpHeaders headers,
            @PathVariable("id") UUID id) {
        return deviceService.get(user(headers), id);
    }

    @PatchMapping("/{id}")
    public DeviceResponse update(
            @RequestHeader HttpHeaders headers,
            @PathVariable("id") UUID id,
            @Valid @RequestBody DeviceRequest request) {
        return deviceService.update(user(headers), id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(
            @RequestHeader HttpHeaders headers,
            @PathVariable("id") UUID id) {
        deviceService.delete(user(headers), id);
    }

    @PostMapping("/{id}/enable")
    public DeviceResponse enable(
            @RequestHeader HttpHeaders headers,
            @PathVariable("id") UUID id) {
        return deviceService.enable(user(headers), id);
    }

    @PostMapping("/{id}/disable")
    public DeviceResponse disable(
            @RequestHeader HttpHeaders headers,
            @PathVariable("id") UUID id) {
        return deviceService.disable(user(headers), id);
    }

    @PostMapping("/{id}/decommission")
    public DeviceResponse decommission(
            @RequestHeader HttpHeaders headers,
            @PathVariable("id") UUID id) {
        return deviceService.decommission(user(headers), id);
    }

    private AuthenticatedUser user(HttpHeaders headers) {
        return userResolver.resolve(headers);
    }
}
