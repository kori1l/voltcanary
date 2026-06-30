package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.voltcanary.analytics.dto.MaintenanceWindowSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MaintenanceWindowDeviceClient {

    private final RestClient restClient;
    private final String internalServiceToken;
    private final String internalServiceName;
    private final MeterRegistry meterRegistry;

    public MaintenanceWindowDeviceClient(
            RestClient.Builder restClientBuilder,
            AnalyticsDownstreamProperties downstreamProperties,
            AnalyticsInternalProperties internalProperties,
            MeterRegistry meterRegistry) {
        this.restClient = restClientBuilder.baseUrl(downstreamProperties.getDeviceService().getBaseUrl()).build();
        this.internalServiceToken = internalProperties.getServiceToken();
        this.internalServiceName = internalProperties.getServiceName();
        this.meterRegistry = meterRegistry;
    }

    public List<MaintenanceWindowSnapshot> changes(Instant updatedAfter, int limit) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            MaintenanceWindowSnapshot[] response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/maintenance-windows/changes")
                            .queryParam("updatedAfter", updatedAfter)
                            .queryParam("limit", limit)
                            .build())
                    .headers(headers -> {
                        headers.set("X-Source-Service", internalServiceName);
                        if (!internalServiceToken.isBlank()) {
                            headers.set("X-Internal-Service-Token", internalServiceToken);
                        }
                    })
                    .retrieve()
                    .body(MaintenanceWindowSnapshot[].class);
            sample.stop(meterRegistry.timer("analytics.maintenance.reconciliation.fetch.duration", "status", "success"));
            return response == null ? List.of() : Arrays.asList(response);
        } catch (RestClientException exception) {
            meterRegistry.counter("analytics.maintenance.reconciliation.fetch.failures", "reason", exception.getClass().getSimpleName()).increment();
            sample.stop(meterRegistry.timer("analytics.maintenance.reconciliation.fetch.duration", "status", "failure"));
            return List.of();
        }
    }
}
