package com.voltcanary.device.controller;

import com.voltcanary.device.dto.CancelMaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceWindowRequest;
import com.voltcanary.device.dto.MaintenanceWindowResponse;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.MaintenanceWindowService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workspaces/{workspace}/maintenance/{account}/{device}")
@RequiredArgsConstructor
public class WorkspaceMaintenanceController {

    private final MaintenanceWindowService windows;
    private final AuthenticatedUserResolver users;

    private AuthenticatedUser actor(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token.replaceFirst("^Bearer ", ""));
        return users.resolve(headers);
    }

    @PostMapping
    public MaintenanceWindowResponse create(
        @RequestHeader("Authorization") String token,
        @PathVariable("workspace") UUID workspace,
        @PathVariable("account") UUID account,
        @PathVariable("device") UUID device,
        @Valid @RequestBody MaintenanceWindowRequest body
    ) {
        return windows.workspaceCreate(actor(token), workspace, account, device, body);
    }

    @PutMapping("/{id}")
    public MaintenanceWindowResponse update(
        @RequestHeader("Authorization") String token,
        @PathVariable("workspace") UUID workspace,
        @PathVariable("account") UUID account,
        @PathVariable("device") UUID device,
        @PathVariable("id") UUID id,
        @Valid @RequestBody MaintenanceWindowRequest body
    ) {
        return windows.workspaceUpdate(actor(token), workspace, account, device, id, body);
    }

    @PostMapping("/{id}/cancel")
    public MaintenanceWindowResponse cancel(
        @RequestHeader("Authorization") String token,
        @PathVariable("workspace") UUID workspace,
        @PathVariable("account") UUID account,
        @PathVariable("device") UUID device,
        @PathVariable("id") UUID id,
        @RequestBody(required = false) CancelMaintenanceWindowRequest body
    ) {
        return windows.workspaceCancel(actor(token), workspace, account, device, id, body);
    }

    @PostMapping("/{id}/finish")
    public MaintenanceWindowResponse finish(
        @RequestHeader("Authorization") String token,
        @PathVariable("workspace") UUID workspace,
        @PathVariable("account") UUID account,
        @PathVariable("device") UUID device,
        @PathVariable("id") UUID id
    ) {
        return windows.workspaceFinish(actor(token), workspace, account, device, id);
    }
}
