package com.voltcanary.analytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AnalyticsDownstreamProperties {

    private DownstreamService telemetryService = new DownstreamService("http://localhost:8084", Duration.ofSeconds(2));
    private DownstreamService mlService = new DownstreamService("http://localhost:8090", Duration.ofSeconds(2));
    private DownstreamService alertService = new DownstreamService("http://localhost:8086", Duration.ofSeconds(2));

    public DownstreamService getTelemetryService() {
        return telemetryService;
    }

    public void setTelemetryService(DownstreamService telemetryService) {
        this.telemetryService = telemetryService;
    }

    public DownstreamService getMlService() {
        return mlService;
    }

    public void setMlService(DownstreamService mlService) {
        this.mlService = mlService;
    }

    public DownstreamService getAlertService() {
        return alertService;
    }

    public void setAlertService(DownstreamService alertService) {
        this.alertService = alertService;
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
