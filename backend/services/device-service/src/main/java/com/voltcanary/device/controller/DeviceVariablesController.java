package com.voltcanary.device.controller;

import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.variable.application.VariableDefinitions;
import com.voltcanary.device.variable.domain.VariableDefinition;
import java.util.List;
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
@RequestMapping("/device-types/{code}/variables")
@RequiredArgsConstructor
public class DeviceVariablesController {
    public record ExpectedVersion(int expectedVersion) { }
    private final VariableDefinitions definitions;
    private final AuthenticatedUserResolver users;

    @GetMapping
    public List<VariableDefinition> list(@RequestHeader HttpHeaders headers, @PathVariable("code") String code) {
        return definitions.list(users.resolve(headers), code);
    }

    @GetMapping("/{id}")
    public VariableDefinitions.Detail detail(@RequestHeader HttpHeaders headers,
            @PathVariable("code") String code, @PathVariable("id") UUID id) {
        return definitions.detail(users.resolve(headers), code, id);
    }

    @PostMapping
    public VariableDefinitions.Detail create(@RequestHeader HttpHeaders headers,
            @PathVariable("code") String code, @RequestBody VariableDefinitions.Draft draft) {
        return definitions.create(users.resolve(headers), code, draft);
    }

    @PostMapping("/{id}/revisions")
    public VariableDefinitions.Detail revise(@RequestHeader HttpHeaders headers,
            @PathVariable("code") String code, @PathVariable("id") UUID id, @RequestBody VariableDefinitions.Draft draft) {
        return definitions.revise(users.resolve(headers), code, id, draft);
    }

    @PostMapping("/{id}/publish")
    public VariableDefinitions.Detail publish(@RequestHeader HttpHeaders headers,
            @PathVariable("code") String code, @PathVariable("id") UUID id, @RequestBody VariableDefinitions.Publication request) {
        return definitions.publish(users.resolve(headers), code, id, request);
    }

    @PostMapping("/{id}/archive")
    public VariableDefinition archive(@RequestHeader HttpHeaders headers, @PathVariable("code") String code,
            @PathVariable("id") UUID id, @RequestBody ExpectedVersion request) {
        return definitions.archive(users.resolve(headers), code, id, request.expectedVersion());
    }
}
