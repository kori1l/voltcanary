package com.voltcanary.device.config;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class InternalServiceAuth {

    public static final String HEADER = "X-Internal-Service-Token";

    private final InternalServiceProperties properties;

    public void require(String token) {
        if (token == null || !token.equals(properties.getServiceToken())) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid internal service token"
            );
        }
    }
}
