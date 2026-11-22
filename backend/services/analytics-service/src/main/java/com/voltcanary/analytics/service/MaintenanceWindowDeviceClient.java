package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.voltcanary.analytics.dto.MaintenanceOutcomeSnapshot;
import com.voltcanary.analytics.dto.MaintenanceWindowSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
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
        @Qualifier("deviceRestClientBuilder") RestClient.Builder restClientBuilder,
        AnalyticsDownstreamProperties downstreamProperties,
        AnalyticsInternalProperties internalProperties,
        MeterRegistry meterRegistry
    ) {
        this.restClient = restClientBuilder
            .baseUrl(downstreamProperties.getDeviceService().getBaseUrl())
            .build();
        this.internalServiceToken = internalProperties.getServiceToken();
        this.internalServiceName = internalProperties.getServiceName();
        this.meterRegistry = meterRegistry;
    }

    public List<MaintenanceWindowSnapshot> changes(Instant updatedAfter, int limit) {
        return changes(updatedAfter, null, limit);
    }

    public List<MaintenanceWindowSnapshot> changes(Instant updatedAfter, UUID afterId, int limit) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            MaintenanceWindowSnapshot[] response = restClient
                .get()
                .uri(uriBuilder ->
                    uriBuilder
                        .path("/internal/maintenance-windows/changes")
                        .queryParam("updatedAfter", updatedAfter)
                        .queryParamIfPresent("afterId", Optional.ofNullable(afterId))
                        .queryParam("limit", limit)
                        .build()
                )
                .headers(headers -> {
                    headers.set("X-Source-Service", internalServiceName);
                    if (!internalServiceToken.isBlank()) {
                        headers.set("X-Internal-Service-Token", internalServiceToken);
                    }
                })
                .retrieve()
                .body(MaintenanceWindowSnapshot[].class);
            sample.stop(
                meterRegistry.timer(
                    "analytics.maintenance.reconciliation.fetch.duration",
                    "status",
                    "success"
                )
            );
            return response == null ? List.of() : Arrays.asList(response);
        } catch (RestClientException exception) {
            meterRegistry
                .counter(
                    "analytics.maintenance.reconciliation.fetch.failures",
                    "reason",
                    exception.getClass().getSimpleName()
                )
                .increment();
            sample.stop(
                meterRegistry.timer(
                    "analytics.maintenance.reconciliation.fetch.duration",
                    "status",
                    "failure"
                )
            );
            return List.of();
        }
    }

    public List<MaintenanceOutcomeSnapshot> outcomeChanges(Instant updatedAfter, int limit) {
        return outcomeChanges(updatedAfter, null, limit);
    }

    public List<MaintenanceOutcomeSnapshot> outcomeChanges(
        Instant updatedAfter,
        UUID afterId,
        int limit
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            MaintenanceOutcomeSnapshot[] response = restClient
                .get()
                .uri(uriBuilder ->
                    uriBuilder
                        .path("/internal/maintenance-outcomes/changes")
                        .queryParam("updatedAfter", updatedAfter)
                        .queryParamIfPresent("afterId", Optional.ofNullable(afterId))
                        .queryParam("limit", limit)
                        .build()
                )
                .headers(headers -> {
                    headers.set("X-Source-Service", internalServiceName);
                    if (!internalServiceToken.isBlank()) {
                        headers.set("X-Internal-Service-Token", internalServiceToken);
                    }
                })
                .retrieve()
                .body(MaintenanceOutcomeSnapshot[].class);
            sample.stop(
                meterRegistry.timer(
                    "analytics.maintenance.outcome.reconciliation.fetch.duration",
                    "status",
                    "success"
                )
            );
            return response == null ? List.of() : Arrays.asList(response);
        } catch (RestClientException exception) {
            meterRegistry
                .counter(
                    "analytics.maintenance.outcome.reconciliation.fetch.failures",
                    "reason",
                    exception.getClass().getSimpleName()
                )
                .increment();
            sample.stop(
                meterRegistry.timer(
                    "analytics.maintenance.outcome.reconciliation.fetch.duration",
                    "status",
                    "failure"
                )
            );
            return List.of();
        }
    }
}
