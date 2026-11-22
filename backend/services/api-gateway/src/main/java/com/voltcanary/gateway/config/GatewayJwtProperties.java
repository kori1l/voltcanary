package com.voltcanary.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.jwt")
@Getter
@Setter
public class GatewayJwtProperties {

    private String keyId = "dev-rsa";
    private String publicKeys = "";
    private String issuer = "voltcanary-identity";
    private String audience = "voltcanary-api";
}
