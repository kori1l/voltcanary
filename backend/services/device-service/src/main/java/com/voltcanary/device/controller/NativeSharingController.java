package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.application.NativeSharingWorkflows;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data/datasets/{dataset}")
@RequiredArgsConstructor
public class NativeSharingController {

    private final AuthenticatedUserResolver users;
    private final NativeSharingWorkflows sharing;

    @PostMapping("/sharing-reviews")
    public JsonNode prepare(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @RequestBody byte[] body
    ) {
        return sharing.prepare(users.resolve(headers), dataset, body);
    }

    @GetMapping("/sharing-reviews/{review}")
    public JsonNode inspect(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @PathVariable UUID review
    ) {
        return sharing.inspect(users.resolve(headers), dataset, review);
    }

    @PostMapping("/sharing-reviews/{review}/apply")
    public JsonNode apply(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @PathVariable UUID review,
        @RequestBody byte[] body
    ) {
        return sharing.apply(users.resolve(headers), dataset, review, body);
    }

    @GetMapping("/sharing")
    public JsonNode list(@RequestHeader HttpHeaders headers, @PathVariable UUID dataset) {
        return sharing.list(users.resolve(headers), dataset);
    }

    @PostMapping("/sharing/{grant}/revoke")
    public JsonNode revoke(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @PathVariable UUID grant,
        @RequestBody byte[] body
    ) {
        return sharing.revoke(users.resolve(headers), dataset, grant, body);
    }
}
