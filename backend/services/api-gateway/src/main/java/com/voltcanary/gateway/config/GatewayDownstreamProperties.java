package com.voltcanary.gateway.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.gateway.downstream-health")
public class GatewayDownstreamProperties {
    private Duration timeout = Duration.ofSeconds(2);
    private Map<String, String> services = new LinkedHashMap<>(Map.of(
            "identity-service", "http://localhost:8081",
            "device-service", "http://localhost:8082",
            "ingestion-service", "http://localhost:8083",
            "telemetry-service", "http://localhost:8084",
            "analytics-service", "http://localhost:8085",
            "alert-service", "http://localhost:8086",
            "simulator-service", "http://localhost:8087",
            "ai-agent-service", "http://localhost:8088"));

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Map<String, String> getServices() { return services; }
    public void setServices(Map<String, String> services) { this.services = services; }
}
