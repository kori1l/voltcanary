package com.voltcanary.dashboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record DashboardJwtProperties(String keyId, String publicKeys, String issuer, String audience) {
}
