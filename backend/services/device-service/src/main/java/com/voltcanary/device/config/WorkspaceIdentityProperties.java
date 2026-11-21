package com.voltcanary.device.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="app.workspace-identity")
@Getter
@Setter
public class WorkspaceIdentityProperties {
    private String baseUrl="http://localhost:8081";

}
