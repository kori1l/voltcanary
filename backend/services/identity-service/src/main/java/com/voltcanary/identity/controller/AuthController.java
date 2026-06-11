package com.voltcanary.identity.controller;

import com.voltcanary.identity.dto.AuthMessageResponse;
import com.voltcanary.identity.dto.AuthTokenResponse;
import com.voltcanary.identity.dto.ChangePasswordRequest;
import com.voltcanary.identity.dto.EmailVerificationConfirmRequest;
import com.voltcanary.identity.dto.EmailVerificationRequest;
import com.voltcanary.identity.dto.ForgotPasswordRequest;
import com.voltcanary.identity.dto.LoginRequest;
import com.voltcanary.identity.dto.MeResponse;
import com.voltcanary.identity.dto.RefreshTokenRequest;
import com.voltcanary.identity.dto.RegisterRequest;
import com.voltcanary.identity.dto.RegisterResponse;
import com.voltcanary.identity.dto.ResetPasswordRequest;
import com.voltcanary.identity.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.voltcanary.identity.dto.AdminRefreshSessionResponse;
import com.voltcanary.identity.dto.AdminRevokeSessionsResponse;
import com.voltcanary.identity.dto.AdminUpdateUserRoleRequest;
import com.voltcanary.identity.dto.AdminUpdateUserStatusRequest;
import com.voltcanary.identity.dto.AdminUserResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthTokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(
                request,
                clientIp(httpRequest),
                httpRequest.getHeader("User-Agent"),
                correlationId(httpRequest));
    }

    @PostMapping("/refresh")
    public AuthTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    public AuthMessageResponse logout(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.logout(request);
    }

    @PostMapping("/logout-all")
    public AuthMessageResponse logoutAll(@RequestHeader(value = "Authorization", required = false)
            String authorizationHeader) {
        return authService.logoutAll(authorizationHeader);
    }

    @PostMapping("/change-password")
    public AuthMessageResponse changePassword(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(authorizationHeader, request);
    }

    @PostMapping("/forgot-password")
    public AuthMessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    public AuthMessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }

    @PostMapping("/email-verification/request")
    public AuthMessageResponse requestEmailVerification(@Valid @RequestBody EmailVerificationRequest request) {
        return authService.requestEmailVerification(request);
    }

    @PostMapping("/email-verification/confirm")
    public AuthMessageResponse confirmEmailVerification(
            @Valid @RequestBody EmailVerificationConfirmRequest request) {
        return authService.confirmEmailVerification(request);
    }

    @GetMapping("/me")
    public MeResponse me(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return authService.me(authorizationHeader);
    }

    @GetMapping("/admin/users")
    public List<AdminUserResponse> listUsers(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return authService.listUsers(authorizationHeader);
    }

    @PatchMapping("/admin/users/{userId}/status")
    public AdminUserResponse updateUserStatus(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody AdminUpdateUserStatusRequest request) {
        return authService.updateUserStatus(authorizationHeader, userId, request);
    }

    @PatchMapping("/admin/users/{userId}/role")
    public AdminUserResponse updateUserRole(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody AdminUpdateUserRoleRequest request) {
        return authService.updateUserRole(authorizationHeader, userId, request);
    }

    @DeleteMapping("/admin/users/{userId}")
    public AdminUserResponse deleteUser(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("userId") UUID userId) {
        return authService.deleteUser(authorizationHeader, userId);
    }

    @GetMapping("/admin/users/{userId}/sessions")
    public List<AdminRefreshSessionResponse> listUserSessions(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("userId") UUID userId) {
        return authService.listUserSessions(authorizationHeader, userId);
    }

    @PostMapping("/admin/users/{userId}/sessions/revoke")
    public AdminRevokeSessionsResponse revokeUserSessions(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable("userId") UUID userId) {
        return authService.revokeUserSessions(authorizationHeader, userId);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",", 2)[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }

    private String correlationId(HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId.trim();
        }
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? null : requestId.trim();
    }
}
