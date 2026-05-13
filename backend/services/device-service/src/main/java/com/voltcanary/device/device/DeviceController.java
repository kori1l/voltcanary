package com.voltcanary.device.device;

import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.security.JwtService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final JwtService jwtService;

    public DeviceController(DeviceService deviceService, JwtService jwtService) {
        this.deviceService = deviceService;
        this.jwtService = jwtService;
    }

    @PostMapping
    public DeviceResponse create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody DeviceRequest request) {
        return deviceService.create(user(authorization), request);
    }

    @GetMapping
    public List<DeviceResponse> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return deviceService.list(user(authorization));
    }

    @GetMapping("/{id}")
    public DeviceResponse get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id) {
        return deviceService.get(user(authorization), id);
    }

    @PatchMapping("/{id}")
    public DeviceResponse update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id,
            @Valid @RequestBody DeviceRequest request) {
        return deviceService.update(user(authorization), id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id) {
        deviceService.delete(user(authorization), id);
    }

    private AuthenticatedUser user(String authorization) {
        return jwtService.parseBearerToken(authorization);
    }
}
