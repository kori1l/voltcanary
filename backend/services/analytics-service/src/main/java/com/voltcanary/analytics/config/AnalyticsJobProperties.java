package com.voltcanary.analytics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.jobs")
@Getter
@Setter
public class AnalyticsJobProperties {

    private boolean async = true;
    private boolean workerEnabled = false;
}
