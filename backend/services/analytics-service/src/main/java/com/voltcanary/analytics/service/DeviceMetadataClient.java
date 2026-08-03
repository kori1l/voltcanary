package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class DeviceMetadataClient {

    private final RestClient restClient;
    private final String internalServiceToken;
    private final String internalServiceName;
    private final MeterRegistry meterRegistry;

    public DeviceMetadataClient(
            RestClient.Builder restClientBuilder,
            AnalyticsDownstreamProperties downstreamProperties,
            AnalyticsInternalProperties internalProperties,
            MeterRegistry meterRegistry) {
        this.restClient = restClientBuilder.baseUrl(downstreamProperties.getDeviceService().getBaseUrl()).build();
        this.internalServiceToken = internalProperties.getServiceToken();
        this.internalServiceName = internalProperties.getServiceName();
        this.meterRegistry = meterRegistry;
    }

    public DeviceMetadata metadata(UUID deviceId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            DeviceMetadata response = restClient.get()
                    .uri("/internal/devices/{deviceId}/metadata", deviceId)
                    .headers(headers -> {
                        headers.set("X-Source-Service", internalServiceName);
                        if (!internalServiceToken.isBlank()) {
                            headers.set("X-Internal-Service-Token", internalServiceToken);
                        }
                    })
                    .retrieve()
                    .body(DeviceMetadata.class);
            sample.stop(meterRegistry.timer("analytics.device.metadata.fetch.duration", "status", "success"));
            return response;
        } catch (RestClientException exception) {
            meterRegistry.counter("analytics.device.metadata.fetch.failures", "reason", exception.getClass().getSimpleName()).increment();
            sample.stop(meterRegistry.timer("analytics.device.metadata.fetch.duration", "status", "failure"));
            return null;
        }
    }

    public record DeviceMetadata(UUID deviceId, UUID accountId, UUID locationId, String type, String status) {
    }
}
