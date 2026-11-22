package com.voltcanary.analytics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.dlq")
@Getter
@Setter
public class AnalyticsDlqProperties {

    private String topic = "analytics.telemetry.validated.dlq.v1";
}
