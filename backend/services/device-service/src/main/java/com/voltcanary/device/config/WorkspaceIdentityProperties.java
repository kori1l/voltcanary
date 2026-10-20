package com.voltcanary.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="app.workspace-identity")
public class WorkspaceIdentityProperties {
    private String baseUrl="http://localhost:8081";
    public String getBaseUrl() {
        return baseUrl;
    }
    public void setBaseUrl(String baseUrl) {
        this.baseUrl=baseUrl;
    }
}
