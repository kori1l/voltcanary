package com.voltcanary.identity.controller;

import com.voltcanary.identity.dto.AuthMessageResponse;
import com.voltcanary.identity.dto.LoginRequest;
import com.voltcanary.identity.dto.RefreshTokenRequest;
import com.voltcanary.identity.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @PostMapping("/register")
    public AuthMessageResponse register(@Valid @RequestBody RegisterRequest request) {
        return new AuthMessageResponse("register endpoint is not implemented yet");
    }

    @PostMapping("/login")
    public AuthMessageResponse login(@Valid @RequestBody LoginRequest request) {
        return new AuthMessageResponse("login endpoint is not implemented yet");
    }

    @PostMapping("/refresh")
    public AuthMessageResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return new AuthMessageResponse("refresh endpoint is not implemented yet");
    }

    @PostMapping("/logout")
    public AuthMessageResponse logout() {
        return new AuthMessageResponse("logout endpoint is not implemented yet");
    }

    @GetMapping("/me")
    public AuthMessageResponse me() {
        return new AuthMessageResponse("current user endpoint is not implemented yet");
    }
}
