package com.voltcanary.analytics.ml;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MlClient {

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final String internalServiceToken;
    private final String internalServiceName;

    @Autowired
    public MlClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.ml-service.timeout:2s}") java.time.Duration timeout,
            @Value("${app.ml-service.base-url:http://localhost:8090}") String baseUrl,
            @Value("${app.internal.service-token:${INTERNAL_SERVICE_TOKEN:dev-internal-token}}") String internalServiceToken,
            @Value("${app.internal.service-name:analytics-service}") String internalServiceName,
            MeterRegistry meterRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalServiceToken = internalServiceToken;
        this.internalServiceName = internalServiceName;
        this.meterRegistry = meterRegistry;
    }

    public MlClient(RestClient restClient) {
        this.restClient = restClient;
        this.internalServiceToken = "";
        this.internalServiceName = "analytics-service";
        this.meterRegistry = new SimpleMeterRegistry();
    }

    public MlInferenceResponse requestPrediction(UUID deviceId, Instant timestamp, Map<String, Object> metrics) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            MlInferenceResponse response = restClient.post()
                    .uri("/inference")
                    .headers(headers -> {
                        headers.set("X-Source-Service", internalServiceName);
                        if (!internalServiceToken.isBlank()) {
                            headers.set("X-Internal-Service-Token", internalServiceToken);
                        }
                    })
                    .body(new MlInferenceRequest(deviceId, timestamp, metrics))
                    .retrieve()
                    .body(MlInferenceResponse.class);
            sample.stop(meterRegistry.timer("analytics.ml.inference.duration", "status", "success"));
            return response;
        } catch (RestClientException exception) {
            meterRegistry.counter("analytics.ml.inference.failures", "reason", exception.getClass().getSimpleName()).increment();
            sample.stop(meterRegistry.timer("analytics.ml.inference.duration", "status", "failure"));
            return null;
        }
    }
}
