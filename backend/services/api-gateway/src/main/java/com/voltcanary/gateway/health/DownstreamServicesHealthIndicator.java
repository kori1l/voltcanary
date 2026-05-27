package com.voltcanary.gateway.health;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component("downstreamServices")
@ConditionalOnProperty(prefix = "app.gateway.downstream-health", name = "enabled", havingValue = "true")
public class DownstreamServicesHealthIndicator implements ReactiveHealthIndicator {

    private final WebClient webClient;
    private final Duration timeout;
    private final Map<String, String> serviceUris;

    public DownstreamServicesHealthIndicator(
            WebClient.Builder webClientBuilder,
            @Value("${app.gateway.downstream-health.timeout:2s}") Duration timeout,
            @Value("${IDENTITY_SERVICE_URI:http://localhost:8081}") String identityServiceUri,
            @Value("${DEVICE_SERVICE_URI:http://localhost:8082}") String deviceServiceUri,
            @Value("${INGESTION_SERVICE_URI:http://localhost:8083}") String ingestionServiceUri,
            @Value("${TELEMETRY_SERVICE_URI:http://localhost:8084}") String telemetryServiceUri,
            @Value("${ANALYTICS_SERVICE_URI:http://localhost:8085}") String analyticsServiceUri,
            @Value("${ALERT_SERVICE_URI:http://localhost:8086}") String alertServiceUri,
            @Value("${SIMULATOR_SERVICE_URI:http://localhost:8087}") String simulatorServiceUri,
            @Value("${AI_AGENT_SERVICE_URI:http://localhost:8088}") String aiAgentServiceUri) {
        this.webClient = webClientBuilder.build();
        this.timeout = timeout;
        this.serviceUris = new LinkedHashMap<>();
        this.serviceUris.put("identity-service", identityServiceUri);
        this.serviceUris.put("device-service", deviceServiceUri);
        this.serviceUris.put("ingestion-service", ingestionServiceUri);
        this.serviceUris.put("telemetry-service", telemetryServiceUri);
        this.serviceUris.put("analytics-service", analyticsServiceUri);
        this.serviceUris.put("alert-service", alertServiceUri);
        this.serviceUris.put("simulator-service", simulatorServiceUri);
        this.serviceUris.put("ai-agent-service", aiAgentServiceUri);
    }

    @Override
    public Mono<Health> health() {
        return Mono.zip(serviceUris.entrySet().stream()
                        .map(entry -> check(entry.getKey(), entry.getValue()))
                        .toList(), results -> results)
                .map(results -> {
                    Health.Builder builder = Health.up();
                    for (Object result : results) {
                        ServiceHealth serviceHealth = (ServiceHealth) result;
                        builder.withDetail(serviceHealth.name(), serviceHealth.status());
                        if (!"UP".equals(serviceHealth.status())) {
                            builder.down();
                        }
                    }
                    return builder.build();
                });
    }

    private Mono<ServiceHealth> check(String name, String uri) {
        return webClient.get()
                .uri(uri + "/actuator/health")
                .retrieve()
                .toBodilessEntity()
                .timeout(timeout)
                .map(response -> new ServiceHealth(name, response.getStatusCode().is2xxSuccessful() ? "UP" : "DOWN"))
                .onErrorReturn(new ServiceHealth(name, "DOWN"));
    }

    private record ServiceHealth(String name, String status) {
    }
}
