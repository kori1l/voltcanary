package com.voltcanary.analytics.telemetry;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatus;
import org.springframework.core.ParameterizedTypeReference;
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
    public TelemetryClient(
            RestClient.Builder restClientBuilder,
            AnalyticsDownstreamProperties downstreamProperties,
            AnalyticsInternalProperties internalProperties,
            MeterRegistry meterRegistry) {
        AnalyticsDownstreamProperties.DownstreamService telemetryService = downstreamProperties.getTelemetryService();
        this.restClient = restClientBuilder.baseUrl(telemetryService.getBaseUrl()).build();
        this.internalServiceToken = internalProperties.getServiceToken();
        this.internalServiceName = internalProperties.getServiceName();
        this.meterRegistry = meterRegistry;
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

    public List<TelemetryMetricResponse> metrics(UUID deviceId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<TelemetryMetricResponse> metrics = restClient.get()
                    .uri("/telemetry/devices/{deviceId}/metrics", deviceId)
                    .headers(headers -> {
                        headers.set("X-Source-Service", internalServiceName);
                        if (!internalServiceToken.isBlank()) {
                            headers.set("X-Internal-Service-Token", internalServiceToken);
                        }
                    })
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Telemetry metrics not found");
                    })
                    .onStatus(status -> status.is5xxServerError(), (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telemetry service unavailable");
                    })
                    .body(new ParameterizedTypeReference<>() {
                    });
            sample.stop(meterRegistry.timer("analytics.telemetry.metrics.duration", "status", "success"));
            return metrics == null ? List.of() : metrics;
        } catch (ResponseStatusException exception) {
            meterRegistry.counter(
                    "analytics.telemetry.metrics.failures",
                    "status",
                    String.valueOf(exception.getStatusCode().value())).increment();
            sample.stop(meterRegistry.timer("analytics.telemetry.metrics.duration", "status", "failure"));
            throw exception;
        } catch (RestClientException exception) {
            meterRegistry.counter("analytics.telemetry.metrics.failures", "status", "io").increment();
            sample.stop(meterRegistry.timer("analytics.telemetry.metrics.duration", "status", "failure"));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telemetry service unavailable", exception);
        }
    }
}
