package com.voltcanary.analytics.telemetry;

import java.time.Instant;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TelemetryClient {

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final String internalServiceToken;
    private final String internalServiceName;

    @Autowired
    public TelemetryClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.telemetry-service.timeout:2s}") java.time.Duration timeout,
            @Value("${app.telemetry-service.base-url:http://localhost:8084}") String telemetryBaseUrl,
            @Value("${app.internal.service-token:${INTERNAL_SERVICE_TOKEN:dev-internal-token}}") String internalServiceToken,
            @Value("${app.internal.service-name:analytics-service}") String internalServiceName,
            MeterRegistry meterRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = restClientBuilder.baseUrl(telemetryBaseUrl).requestFactory(requestFactory).build();
        this.internalServiceToken = internalServiceToken;
        this.internalServiceName = internalServiceName;
        this.meterRegistry = meterRegistry;
    }

    public TelemetryClient(RestClient restClient) {
        this.restClient = restClient;
        this.internalServiceToken = "";
        this.internalServiceName = "analytics-service";
        this.meterRegistry = new SimpleMeterRegistry();
    }

    public TelemetryChartResponse readings(UUID deviceId, String metric, Instant from, Instant to) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TelemetryChartResponse chartResponse = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/telemetry/devices/{deviceId}/readings")
                            .queryParam("metric", metric)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .build(deviceId))
                    .headers(headers -> {
                        headers.set("X-Source-Service", internalServiceName);
                        if (!internalServiceToken.isBlank()) {
                            headers.set("X-Internal-Service-Token", internalServiceToken);
                        }
                    })
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Telemetry not found");
                    })
                    .onStatus(status -> status.is5xxServerError(), (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telemetry service unavailable");
                    })
                    .body(TelemetryChartResponse.class);
            sample.stop(meterRegistry.timer("analytics.telemetry.fetch.duration", "status", "success", "metric", metric));
            return chartResponse;
        } catch (ResponseStatusException exception) {
            meterRegistry.counter(
                    "analytics.telemetry.fetch.failures",
                    "status",
                    String.valueOf(exception.getStatusCode().value()),
                    "metric",
                    metric).increment();
            sample.stop(meterRegistry.timer("analytics.telemetry.fetch.duration", "status", "failure", "metric", metric));
            throw exception;
        } catch (RestClientException exception) {
            meterRegistry.counter("analytics.telemetry.fetch.failures", "status", "io", "metric", metric).increment();
            sample.stop(meterRegistry.timer("analytics.telemetry.fetch.duration", "status", "failure", "metric", metric));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telemetry service unavailable", exception);
        }
    }
}
