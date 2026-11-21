package com.voltcanary.device.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowQueries;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Shared grammar publication is separate from the owner's implemented execution profile. */
@RestController
@RequiredArgsConstructor
public class DataLanguageController {
    private final AuthenticatedUserResolver users;
    private final VclRegistry registry = new VclRegistry(new VclParser());

    @GetMapping("/data/language")
    public JsonNode language(@RequestHeader HttpHeaders headers) {
        users.resolve(headers);
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        result.set("registry", registry.capabilities());
        result.set("nativeQuery", JdbcNativeRowQueries.capabilities());
        result.put("ruleExecutionReady", false);
        return result;
    }
}
