package com.voltcanary.device.controller;

import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.variable.application.VariableDefaults;
import com.voltcanary.device.variable.application.VariableRollouts;
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
@RequestMapping("/device-types/{code}/variables/{id}")
@RequiredArgsConstructor
public class VariableRolloutsController {

    private final VariableDefaults defaults;
    private final VariableRollouts rollouts;
    private final AuthenticatedUserResolver users;

    @PutMapping("/default-binding")
    public Map<String, String> configure(
        @RequestHeader HttpHeaders headers,
        @PathVariable("code") String code,
        @PathVariable("id") UUID id,
        @RequestBody VariableDefaults.Edit edit
    ) {
        defaults.configure(users.resolve(headers), code, id, edit);
        return Map.of("status", "SAVED");
    }

    @PostMapping("/inheritance/reconcile")
    public List<VariableDefaults.Outcome> reconcile(
        @RequestHeader HttpHeaders headers,
        @PathVariable("code") String code,
        @PathVariable("id") UUID id
    ) {
        return defaults.reconcile(users.resolve(headers), code, id);
    }

    @PostMapping("/rollouts")
    public List<VariableRollouts.Result> create(
        @RequestHeader HttpHeaders headers,
        @PathVariable("code") String code,
        @PathVariable("id") UUID id,
        @RequestBody VariableRollouts.Request request
    ) {
        return rollouts.create(users.resolve(headers), code, id, request);
    }

    @GetMapping("/rollouts")
    public List<VariableRollouts.Result> list(
        @RequestHeader HttpHeaders headers,
        @PathVariable("code") String code,
        @PathVariable("id") UUID id
    ) {
        return rollouts.list(users.resolve(headers), code, id);
    }

    @PostMapping("/rollouts/{targetId}/resume")
    public VariableRollouts.Result resume(
        @RequestHeader HttpHeaders headers,
        @PathVariable("code") String code,
        @PathVariable("id") UUID id,
        @PathVariable("targetId") UUID targetId
    ) {
        return rollouts.resume(users.resolve(headers), code, id, targetId);
    }
}
