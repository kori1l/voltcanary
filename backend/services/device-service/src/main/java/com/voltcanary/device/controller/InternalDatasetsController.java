package com.voltcanary.device.controller;

import com.voltcanary.device.config.InternalServiceAuth;
import com.voltcanary.device.configurable.application.DatasetDescriptions;
import com.voltcanary.vcl.VclDatasetDescription;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/data/datasets")
public class InternalDatasetsController {
    private final DatasetDescriptions descriptions;
    private final InternalServiceAuth auth;

    public InternalDatasetsController(DatasetDescriptions descriptions, InternalServiceAuth auth) {
        this.descriptions = descriptions;
        this.auth = auth;
    }

    @GetMapping("/{id}/schemas/{revision}")
    public VclDatasetDescription describe(@RequestHeader(value = InternalServiceAuth.HEADER, required = false) String token,
            @PathVariable UUID id, @PathVariable long revision, @RequestParam UUID userId) {
        auth.require(token);
        return descriptions.describe(userId, id, revision);
    }
}
