package com.voltcanary.alert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AlertDownstreamProperties {

    private DownstreamService analyticsService = new DownstreamService("http://localhost:8085", Duration.ofSeconds(3));
    private DownstreamService deviceService = new DownstreamService("http://localhost:8082", Duration.ofSeconds(3));

    public DownstreamService getAnalyticsService() {
        return analyticsService;
    }

    public void setAnalyticsService(DownstreamService analyticsService) {
        this.analyticsService = analyticsService;
    }

    public DownstreamService getDeviceService() {
        return deviceService;
    }

    public void setDeviceService(DownstreamService deviceService) {
        this.deviceService = deviceService;
    }

    public static class DownstreamService {
        private String baseUrl;
        private Duration timeout;

        public DownstreamService() {
        }

        public DownstreamService(String baseUrl, Duration timeout) {
            this.baseUrl = baseUrl;
            this.timeout = timeout;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
