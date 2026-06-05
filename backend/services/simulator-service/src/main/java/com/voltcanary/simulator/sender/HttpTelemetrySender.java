package com.voltcanary.simulator.sender;

import com.voltcanary.simulator.generator.GeneratedTelemetryPoint;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpTelemetrySender implements TelemetrySender {

    private final RestClient restClient;

    public HttpTelemetrySender(RestClient.Builder restClientBuilder, SimulatorSenderProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getHttp().getIngestionUrl()).build();
    }

    @Override
    public boolean supports(String protocol) {
        return "HTTP".equalsIgnoreCase(protocol);
    }

    @Override
    public void send(GeneratedTelemetryPoint point, String sourceToken) {
        restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + sourceToken)
                .body(Map.of(
                        "sourceId", point.sourceId(),
                        "timestamp", point.timestamp(),
                        "metrics", point.metrics()))
                .retrieve()
                .toBodilessEntity();
    }
}
