package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.application.NativeMigrationWorkflows;
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
public class NativeMigrationsController {

    private final AuthenticatedUserResolver users;
    private final NativeMigrationWorkflows migrations;

    @PostMapping("/migration-reviews")
    public JsonNode reserve(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @RequestBody byte[] body
    ) {
        return migrations.reserve(users.resolve(headers), dataset, body);
    }

    @PostMapping("/migration-reviews/{review}/prepare")
    public JsonNode prepare(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @PathVariable UUID review,
        @RequestBody byte[] body
    ) {
        return migrations.prepare(users.resolve(headers), dataset, review, body);
    }

    @GetMapping("/migration-reviews/{review}")
    public JsonNode inspect(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @PathVariable UUID review
    ) {
        return migrations.inspect(users.resolve(headers), dataset, review);
    }

    @PostMapping("/migration-reviews/{review}/apply")
    public JsonNode apply(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @PathVariable UUID review,
        @RequestBody byte[] body
    ) {
        return migrations.apply(users.resolve(headers), dataset, review, body);
    }

    @PostMapping("/archive")
    public JsonNode archive(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID dataset,
        @RequestBody byte[] body
    ) {
        return migrations.archive(users.resolve(headers), dataset, body);
    }
}
