package com.voltcanary.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.jwt")
public record UserJwtProperties(String keyId, String publicKeys, String issuer, String audience) {
    public UserJwtProperties {
        keyId = keyId == null ? "dev-rsa" : keyId;
        publicKeys = publicKeys == null ? "" : publicKeys;
        issuer = issuer == null ? "voltcanary-identity" : issuer;
        audience = audience == null ? "voltcanary-api" : audience;
    }
}
