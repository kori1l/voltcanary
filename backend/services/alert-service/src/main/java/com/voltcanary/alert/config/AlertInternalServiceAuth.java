package com.voltcanary.alert.config;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class AlertInternalServiceAuth {

    public static final String HEADER = "X-Internal-Service-Token";

    private final AlertInternalProperties properties;

    public void require(String token) {
        if (token == null || !token.equals(properties.getServiceToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service token");
        }
    }
}
