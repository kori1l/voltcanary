package com.voltcanary.device.controller;

import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.telemetry.TelemetryTemplates;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices/telemetry-templates")
@RequiredArgsConstructor
public class TelemetryTemplatesController {

    private final TelemetryTemplates templates;
    private final AuthenticatedUserResolver users;

    @GetMapping
    public List<TelemetryTemplates.Template> list(@RequestHeader HttpHeaders headers) {
        return templates.list(users.resolve(headers));
    }

    @PostMapping
    public TelemetryTemplates.Template create(
        @RequestHeader HttpHeaders headers,
        @RequestBody TelemetryTemplates.Edit edit
    ) {
        return templates.save(users.resolve(headers), null, edit);
    }

    @PutMapping("/{id}")
    public TelemetryTemplates.Template update(
        @RequestHeader HttpHeaders headers,
        @PathVariable("id") UUID id,
        @RequestBody TelemetryTemplates.Edit edit
    ) {
        return templates.save(users.resolve(headers), id, edit);
    }

    @PostMapping("/assign")
    public Map<String, Integer> assign(
        @RequestHeader HttpHeaders headers,
        @RequestBody TelemetryTemplates.Assignment request
    ) {
        return Map.of("assigned", templates.assign(users.resolve(headers), request));
    }
}
