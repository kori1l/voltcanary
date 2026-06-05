package com.voltcanary.ingestion.device;

import com.voltcanary.ingestion.config.IngestionProperties;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DeviceValidationClient {

    private final RestClient restClient;
    private final String internalServiceToken;

    public DeviceValidationClient(
            RestClient.Builder restClientBuilder,
            IngestionProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getDeviceServiceBaseUrl()).build();
        this.internalServiceToken = properties.getInternalServiceToken();
    }

    public boolean dataSourceExists(UUID deviceId, String sourceId) {
        Map<String, Boolean> response = restClient.get()
                .uri("/internal/devices/{deviceId}/data-sources/{sourceId}/exists", deviceId, sourceId)
                .header("X-Internal-Service-Token", internalServiceToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                })
                .body(new ParameterizedTypeReference<>() {
                });
        return response != null && Boolean.TRUE.equals(response.get("exists"));
    }
}
