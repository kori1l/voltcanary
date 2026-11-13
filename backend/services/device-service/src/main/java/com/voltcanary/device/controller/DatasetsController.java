package com.voltcanary.device.controller;

import com.voltcanary.device.configurable.application.DatasetDescriptions;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.vcl.VclDatasetDescription;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data/datasets")
public class DatasetsController {
    private final DatasetDescriptions descriptions;
    private final AuthenticatedUserResolver users;

    public DatasetsController(DatasetDescriptions descriptions, AuthenticatedUserResolver users) {
        this.descriptions = descriptions;
        this.users = users;
    }

    @GetMapping("/{id}")
    public VclDatasetDescription describe(@RequestHeader HttpHeaders headers, @PathVariable UUID id,
            @RequestParam(defaultValue = "1") long schemaRevision) {
        return descriptions.describe(users.resolve(headers), id, schemaRevision);
    }

    @GetMapping
    public java.util.List<DatasetDescriptions.Summary> list(@RequestHeader HttpHeaders headers) {
        return descriptions.list(users.resolve(headers));
    }
}
