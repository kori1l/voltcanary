package com.voltcanary.analytics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.internal")
@Getter
@Setter
public class AnalyticsInternalProperties {

    private String serviceToken = "dev-internal-token";
    private String serviceName = "analytics-service";

}
