package com.voltcanary.alert.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.internal")
@Getter
@Setter
public class AlertInternalProperties {

    private String serviceToken = "dev-internal-token";
    private String serviceName = "alert-service";
}
