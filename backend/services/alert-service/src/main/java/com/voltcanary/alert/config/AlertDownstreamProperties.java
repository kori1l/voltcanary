package com.voltcanary.alert.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AlertDownstreamProperties {

    private DownstreamService analyticsService = new DownstreamService("http://localhost:8085", Duration.ofSeconds(3));
    private DownstreamService deviceService = new DownstreamService("http://localhost:8082", Duration.ofSeconds(3));

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
