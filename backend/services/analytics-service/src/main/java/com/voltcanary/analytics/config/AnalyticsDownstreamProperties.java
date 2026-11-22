package com.voltcanary.analytics.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AnalyticsDownstreamProperties {

    private Duration mlTrainingTimeout = Duration.ofMinutes(10);

    private DownstreamService telemetryService = new DownstreamService("http://localhost:8084", Duration.ofSeconds(2));
    private DownstreamService mlService = new DownstreamService("http://localhost:8090", Duration.ofSeconds(2));
    private DownstreamService alertService = new DownstreamService("http://localhost:8086", Duration.ofSeconds(2));
    private DownstreamService deviceService = new DownstreamService("http://localhost:8082", Duration.ofSeconds(2));

    public static class DownstreamService {
        @Getter
        @Setter
        private String baseUrl;
        @Getter
        @Setter
        private Duration timeout;

        public DownstreamService() {
        }

        public DownstreamService(String baseUrl, Duration timeout) {
            this.baseUrl = baseUrl;
            this.timeout = timeout;
        }

    }
}
