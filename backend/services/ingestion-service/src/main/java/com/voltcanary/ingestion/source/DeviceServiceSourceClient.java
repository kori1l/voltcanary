package com.voltcanary.ingestion.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DeviceServiceSourceClient {

    private final RestClient restClient;

    public DeviceServiceSourceClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.device-service.base-url:http://localhost:8082}") String deviceServiceBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(deviceServiceBaseUrl).build();
    }

    public InternalDataSourceResponse findByTokenPrefix(String tokenPrefix) {
        return restClient.get()
                .uri("/internal/data-sources/by-token-prefix/{tokenPrefix}", tokenPrefix)
                .retrieve()
                .body(InternalDataSourceResponse.class);
    }

    public InternalDataSourceResponse findByExternalId(String externalId) {
        return restClient.get()
                .uri("/internal/data-sources/by-external-id/{externalId}", externalId)
                .retrieve()
                .body(InternalDataSourceResponse.class);
    }

    public void markTokenUsed(java.util.UUID dataSourceId) {
        restClient.post()
                .uri("/internal/data-sources/{dataSourceId}/token-used", dataSourceId)
                .retrieve()
                .toBodilessEntity();
    }
}
