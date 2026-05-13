package com.voltcanary.identity.controller;

import com.voltcanary.identity.dto.AuthMessageResponse;
import com.voltcanary.identity.dto.AuthTokenResponse;
import com.voltcanary.identity.dto.LoginRequest;
import com.voltcanary.identity.dto.MeResponse;
import com.voltcanary.identity.dto.RefreshTokenRequest;
import com.voltcanary.identity.dto.RegisterRequest;
import com.voltcanary.identity.dto.RegisterResponse;
import com.voltcanary.identity.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
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
    public AuthTokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    public AuthMessageResponse logout() {
        return new AuthMessageResponse("logout endpoint is not implemented yet");
    }

    @GetMapping("/me")
    public MeResponse me(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return authService.me(authorizationHeader);
    }
}
