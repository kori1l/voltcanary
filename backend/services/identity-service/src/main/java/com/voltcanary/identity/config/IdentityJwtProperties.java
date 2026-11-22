package com.voltcanary.identity.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.jwt")
@Getter
@Setter
public class IdentityJwtProperties {

    private String keyId = "dev-rsa";
    private String privateKey = "";
    private String publicKeys = "";
    private long accessTokenTtlSeconds = 900;
    private long refreshTokenTtlSeconds = 2_592_000;
    private String issuer = "voltcanary-identity";
    private String audience = "voltcanary-api";
}
