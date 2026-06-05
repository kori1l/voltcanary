package com.voltcanary.ingestion.source;

import com.voltcanary.ingestion.config.IngestionProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DeviceServiceSourceClient {

    private final RestClient restClient;
    private final String internalServiceToken;

    public DeviceServiceSourceClient(
            RestClient.Builder restClientBuilder,
            IngestionProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getDeviceServiceBaseUrl()).build();
        this.internalServiceToken = properties.getInternalServiceToken();
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
