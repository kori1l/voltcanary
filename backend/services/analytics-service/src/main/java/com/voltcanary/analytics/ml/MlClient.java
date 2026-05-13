package com.voltcanary.analytics.ml;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MlClient {

    private final RestClient restClient;

    public MlClient(RestClient.Builder restClientBuilder, @Value("${app.ml-service.base-url:http://localhost:8090}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public MlInferenceResponse requestPrediction(UUID deviceId, Instant timestamp, Map<String, Object> metrics) {
        return restClient.post()
                .uri("/inference")
                .body(new MlInferenceRequest(deviceId, timestamp, metrics))
                .retrieve()
                .body(MlInferenceResponse.class);
    }
}
