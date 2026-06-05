package com.voltcanary.device.controller;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.dto.LocationRequest;
import com.voltcanary.device.dto.LocationResponse;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.LocationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
@RequestMapping("/locations")
public class LocationController {

    private final LocationService locationService;
    private final AuthenticatedUserResolver userResolver;

    public LocationController(LocationService locationService, AuthenticatedUserResolver userResolver) {
        this.locationService = locationService;
        this.userResolver = userResolver;
    }

    @PostMapping
    public LocationResponse create(
            @RequestHeader HttpHeaders headers,
            @Valid @RequestBody LocationRequest request) {
        return locationService.create(user(headers), request);
    }

    @GetMapping
    public List<LocationResponse> list(
            @RequestHeader HttpHeaders headers,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        return locationService.list(user(headers), search, limit, offset);
    }

    @GetMapping("/{id}")
    public LocationResponse get(
            @RequestHeader HttpHeaders headers,
            @PathVariable("id") UUID id) {
        return locationService.get(user(headers), id);
    }

    @PatchMapping("/{id}")
    public LocationResponse update(
            @RequestHeader HttpHeaders headers,
            @PathVariable("id") UUID id,
            @Valid @RequestBody LocationRequest request) {
        return locationService.update(user(headers), id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(
            @RequestHeader HttpHeaders headers,
            @PathVariable("id") UUID id) {
        locationService.delete(user(headers), id);
    }

    private AuthenticatedUser user(HttpHeaders headers) {
        return userResolver.resolve(headers);
    }
}
