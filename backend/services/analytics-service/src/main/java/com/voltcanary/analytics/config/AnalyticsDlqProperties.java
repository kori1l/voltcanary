package com.voltcanary.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.dlq")
public class AnalyticsDlqProperties {

    private String topic = "analytics.telemetry.validated.dlq.v1";

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
