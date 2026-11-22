package com.voltcanary.device.controller;

import com.voltcanary.device.configurable.application.DatasetDescriptions;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.vcl.VclDatasetDescription;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data/datasets")
@RequiredArgsConstructor
public class DatasetsController {

    private final DatasetDescriptions descriptions;
    private final AuthenticatedUserResolver users;

    @GetMapping("/{id}")
    public VclDatasetDescription describe(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestParam(required = false) Long schemaRevision
    ) {
        if (schemaRevision != null && schemaRevision < 1) throw new com.voltcanary.vcl.VclException(
            "TARGET_INVALID",
            ""
        );
        return descriptions.describe(
            users.resolve(headers),
            id,
            schemaRevision == null ? 0 : schemaRevision
        );
    }

    @GetMapping("/{id}/capabilities")
    public DatasetDescriptions.Capabilities capabilities(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        jakarta.servlet.http.HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return descriptions.capabilities(users.resolve(headers), id);
    }

    @GetMapping
    public java.util.List<DatasetDescriptions.Summary> list(@RequestHeader HttpHeaders headers) {
        return descriptions.list(users.resolve(headers));
    }

    @GetMapping("/archived")
    public java.util.List<com.voltcanary.device.configurable.port.NativeDatasetCatalog.ArchivedTable> archived(
        @RequestHeader HttpHeaders headers
    ) {
        return descriptions.archived(users.resolve(headers));
    }
}
