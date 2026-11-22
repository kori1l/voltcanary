package com.voltcanary.identity.controller;

import com.voltcanary.identity.dto.AdminRefreshSessionResponse;
import com.voltcanary.identity.dto.AdminRevokeSessionsResponse;
import com.voltcanary.identity.dto.AdminUpdateUserRoleRequest;
import com.voltcanary.identity.dto.AdminUpdateUserStatusRequest;
import com.voltcanary.identity.dto.AdminUserResponse;
import com.voltcanary.identity.service.AdminIdentityService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/admin")
@RequiredArgsConstructor
public class AdminIdentityController {

    private final AdminIdentityService service;

    @GetMapping("/users")
    public List<AdminUserResponse> listUsers(
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int size,
        @RequestParam(defaultValue = "") String search
    ) {
        return service.listUsers(authorizationHeader, page, size, search);
    }

    @PatchMapping("/users/{userId}/status")
    public AdminUserResponse updateUserStatus(
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
        @PathVariable("userId") UUID userId,
        @Valid @RequestBody AdminUpdateUserStatusRequest request
    ) {
        return service.updateUserStatus(authorizationHeader, userId, request);
    }

    @PatchMapping("/users/{userId}/role")
    public AdminUserResponse updateUserRole(
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
        @PathVariable("userId") UUID userId,
        @Valid @RequestBody AdminUpdateUserRoleRequest request
    ) {
        return service.updateUserRole(authorizationHeader, userId, request);
    }

    @DeleteMapping("/users/{userId}")
    public AdminUserResponse deleteUser(
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
        @PathVariable("userId") UUID userId
    ) {
        return service.deleteUser(authorizationHeader, userId);
    }

    @GetMapping("/users/{userId}/sessions")
    public List<AdminRefreshSessionResponse> listUserSessions(
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
        @PathVariable("userId") UUID userId
    ) {
        return service.listUserSessions(authorizationHeader, userId);
    }

    @PostMapping("/users/{userId}/sessions/revoke")
    public AdminRevokeSessionsResponse revokeUserSessions(
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
        @PathVariable("userId") UUID userId
    ) {
        return service.revokeUserSessions(authorizationHeader, userId);
    }
}
