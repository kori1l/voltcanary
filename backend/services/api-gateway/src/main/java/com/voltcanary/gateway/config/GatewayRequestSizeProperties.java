package com.voltcanary.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.gateway.request-size")
public class GatewayRequestSizeProperties {

    private long defaultMaxBytes = 1_048_576;
    private long authMaxBytes = 65_536;
    private long ingestionMaxBytes = 262_144;

    public long getDefaultMaxBytes() {
        return defaultMaxBytes;
    }

    public void setDefaultMaxBytes(long defaultMaxBytes) {
        this.defaultMaxBytes = defaultMaxBytes;
    }

    public long getAuthMaxBytes() {
        return authMaxBytes;
    }

    public void setAuthMaxBytes(long authMaxBytes) {
        this.authMaxBytes = authMaxBytes;
    }

    public long getIngestionMaxBytes() {
        return ingestionMaxBytes;
    }

    public void setIngestionMaxBytes(long ingestionMaxBytes) {
        this.ingestionMaxBytes = ingestionMaxBytes;
    }
}
