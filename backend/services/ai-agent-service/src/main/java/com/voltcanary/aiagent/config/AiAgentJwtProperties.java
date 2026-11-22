package com.voltcanary.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.jwt")
@Getter
@Setter
public class AiAgentJwtProperties {

    private String keyId = "dev-rsa";
    private String publicKeys = "";
    private String issuer = "voltcanary-identity";
    private String audience = "voltcanary-api";
}
