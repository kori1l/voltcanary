package com.voltcanary.device.location;

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
@RequestMapping("/locations")
public class LocationController {

    private final LocationService locationService;
    private final JwtService jwtService;

    public LocationController(LocationService locationService, JwtService jwtService) {
        this.locationService = locationService;
        this.jwtService = jwtService;
    }

    @PostMapping
    public LocationResponse create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody LocationRequest request) {
        return locationService.create(user(authorization), request);
    }

    @GetMapping
    public List<LocationResponse> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return locationService.list(user(authorization));
    }

    @GetMapping("/{id}")
    public LocationResponse get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id) {
        return locationService.get(user(authorization), id);
    }

    @PatchMapping("/{id}")
    public LocationResponse update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id,
            @Valid @RequestBody LocationRequest request) {
        return locationService.update(user(authorization), id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") UUID id) {
        locationService.delete(user(authorization), id);
    }

    private AuthenticatedUser user(String authorization) {
        return jwtService.parseBearerToken(authorization);
    }
}
