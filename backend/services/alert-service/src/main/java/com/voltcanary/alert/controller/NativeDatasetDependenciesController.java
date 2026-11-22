package com.voltcanary.alert.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.alert.config.AlertInternalServiceAuth;
import com.voltcanary.alert.configurable.adapter.NativeDatasetDependencies;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NativeDatasetDependenciesController {

    private final NativeDatasetDependencies dependencies;
    private final AlertInternalServiceAuth auth;

    @GetMapping("/internal/native-datasets/{dataset}/dependencies")
    public JsonNode inspect(
        @RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
        @PathVariable UUID dataset,
        @RequestParam UUID accountId,
        @RequestParam long schemaRevision
    ) {
        auth.require(token);
        return dependencies.inspect(accountId, dataset, schemaRevision);
    }
}
