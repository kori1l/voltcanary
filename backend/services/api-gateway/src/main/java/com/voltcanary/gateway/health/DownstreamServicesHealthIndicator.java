package com.voltcanary.gateway.health;

import com.voltcanary.gateway.config.GatewayDownstreamProperties;
import java.time.Duration;
import java.util.Map;
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
            GatewayDownstreamProperties properties) {
        this.webClient = webClientBuilder.build();
        this.timeout = properties.getTimeout();
        this.serviceUris = properties.getServices();
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
