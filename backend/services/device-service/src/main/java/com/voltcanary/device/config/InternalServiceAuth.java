package com.voltcanary.device.config;

import com.voltcanary.device.domain.Device;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;


@Component
public class InternalServiceAuth {

    public static final String HEADER = "X-Internal-Service-Token";

    private final InternalServiceProperties properties;

    public InternalServiceAuth(InternalServiceProperties properties) {
        this.properties = properties;
    }

    public void require(String token) {
        if (token == null || !token.equals(properties.getServiceToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service token");
        }
    }
}
