package com.voltcanary.analytics.telemetry;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TelemetryClient {

    private final RestClient restClient;

    public TelemetryClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.telemetry-service.base-url:http://localhost:8084}") String telemetryBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(telemetryBaseUrl).build();
    }

    public TelemetryChartResponse readings(UUID deviceId, String metric, Instant from, Instant to) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/telemetry/devices/{deviceId}/readings")
                        .queryParam("metric", metric)
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build(deviceId))
                .retrieve()
                .body(TelemetryChartResponse.class);
    }
}
