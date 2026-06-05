package com.voltcanary.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.kafka")
public class DeviceKafkaProperties {
    private boolean publishEnabled = true;

    public boolean isPublishEnabled() { return publishEnabled; }
    public void setPublishEnabled(boolean publishEnabled) { this.publishEnabled = publishEnabled; }
}
