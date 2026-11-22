package com.voltcanary.device.controller;

import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.DeviceDeletionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DeviceDeletionController {

    public record Request(@NotNull Boolean deleteHistory) {}

    private final AuthenticatedUserResolver users;
    private final DeviceDeletionService deletions;

    @PostMapping("/devices/{id}/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeviceDeletionService.Receipt delete(
        @RequestHeader HttpHeaders headers,
        @PathVariable("id") UUID id,
        @Valid @RequestBody Request request
    ) {
        return deletions.begin(users.resolve(headers), id, request.deleteHistory());
    }

    @GetMapping("/devices/deletions")
    public List<DeviceDeletionService.Receipt> list(@RequestHeader HttpHeaders headers) {
        return deletions.list(users.resolve(headers));
    }

    @GetMapping("/devices/{id}/deletion")
    public DeviceDeletionService.Receipt status(
        @RequestHeader HttpHeaders headers,
        @PathVariable("id") UUID id
    ) {
        return deletions.receipt(users.resolve(headers), id);
    }
}
