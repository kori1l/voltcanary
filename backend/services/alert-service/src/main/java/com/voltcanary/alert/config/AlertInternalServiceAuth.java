package com.voltcanary.alert.config;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AlertInternalServiceAuth {

    public static final String HEADER = "X-Internal-Service-Token";

    private final AlertInternalProperties properties;

    public AlertInternalServiceAuth(AlertInternalProperties properties) {
        this.properties = properties;
    }

    public void require(String token) {
        if (token == null || !token.equals(properties.getServiceToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service token");
        }
    }
}
