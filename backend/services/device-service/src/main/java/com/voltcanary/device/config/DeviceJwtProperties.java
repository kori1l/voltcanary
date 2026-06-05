package com.voltcanary.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.jwt")
public class DeviceJwtProperties {
    private String keyId = "dev-rsa";
    private String publicKeys = "";
    private String issuer = "voltcanary-identity";
    private String audience = "voltcanary-api";

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getPublicKeys() { return publicKeys; }
    public void setPublicKeys(String publicKeys) { this.publicKeys = publicKeys; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
}
