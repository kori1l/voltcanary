package com.voltcanary.ingestion.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DeviceServiceSourceClient {

    private final RestClient restClient;
    private final String internalServiceToken;

    public DeviceServiceSourceClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.device-service.base-url:http://localhost:8082}") String deviceServiceBaseUrl,
            @Value("${app.internal.service-token:${INTERNAL_SERVICE_TOKEN:dev-internal-token}}") String internalServiceToken) {
        this.restClient = restClientBuilder.baseUrl(deviceServiceBaseUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    public InternalDataSourceResponse findByTokenPrefix(String tokenPrefix) {
        return restClient.get()
                .uri("/internal/data-sources/by-token-prefix/{tokenPrefix}", tokenPrefix)
                .header("X-Internal-Service-Token", internalServiceToken)
                .retrieve()
                .body(InternalDataSourceResponse.class);
    }

    public InternalDataSourceResponse findByExternalId(String externalId) {
        return restClient.get()
                .uri("/internal/data-sources/by-external-id/{externalId}", externalId)
                .header("X-Internal-Service-Token", internalServiceToken)
                .retrieve()
                .body(InternalDataSourceResponse.class);
    }

    public void markTokenUsed(java.util.UUID dataSourceId) {
        restClient.post()
                .uri("/internal/data-sources/{dataSourceId}/token-used", dataSourceId)
                .header("X-Internal-Service-Token", internalServiceToken)
                .retrieve()
                .toBodilessEntity();
    }
}
