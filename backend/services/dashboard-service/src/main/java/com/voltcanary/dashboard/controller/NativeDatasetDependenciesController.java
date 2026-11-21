package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.config.DashboardReadProperties;
import com.voltcanary.dashboard.query.NativeDatasetDependencies;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class NativeDatasetDependenciesController {
    private final NativeDatasetDependencies dependencies;
    private final DashboardReadProperties properties;

    @GetMapping("/internal/native-datasets/{dataset}/dependencies")
    public JsonNode inspect(@RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
            @PathVariable UUID dataset, @RequestParam UUID accountId, @RequestParam long schemaRevision) {
        String expected = properties.internalServiceToken();
        if (token == null || expected == null || expected.isBlank() || !token.equals(expected)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Internal service authorization required");
        }
        return dependencies.inspect(accountId, dataset, schemaRevision);
    }
}
