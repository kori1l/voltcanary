package com.voltcanary.device.controller;

import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.variable.application.VariableBindings;
import com.voltcanary.device.variable.domain.VariableBinding;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices/{deviceId}/data-sources/{sourceId}/variable-bindings")
public class VariableBindingsController {
    private final VariableBindings bindings;
    private final AuthenticatedUserResolver users;

    public VariableBindingsController(VariableBindings bindings, AuthenticatedUserResolver users) {
        this.bindings = bindings;
        this.users = users;
    }

    @GetMapping
    public List<VariableBinding> list(@RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId, @PathVariable("sourceId") UUID sourceId) {
        return bindings.list(users.resolve(headers), deviceId, sourceId);
    }

    @PutMapping
    public List<VariableBinding> save(@RequestHeader HttpHeaders headers, @PathVariable("deviceId") UUID deviceId,
            @PathVariable("sourceId") UUID sourceId, @RequestBody VariableBindings.Edit edit) {
        return bindings.save(users.resolve(headers), deviceId, sourceId, edit);
    }

    @GetMapping("/status")
    public java.util.Map<String, Object> status(@RequestHeader HttpHeaders headers,
            @PathVariable("deviceId") UUID deviceId, @PathVariable("sourceId") UUID sourceId) {
        return bindings.status(users.resolve(headers), deviceId, sourceId);
    }
}
