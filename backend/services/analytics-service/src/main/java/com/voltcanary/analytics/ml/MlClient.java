package com.voltcanary.analytics.ml;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MlClient {

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final String internalServiceToken;
    private final String internalServiceName;
    public MlClient(
            RestClient.Builder restClientBuilder,
            AnalyticsDownstreamProperties downstreamProperties,
            AnalyticsInternalProperties internalProperties,
            MeterRegistry meterRegistry) {
        AnalyticsDownstreamProperties.DownstreamService mlService = downstreamProperties.getMlService();
        this.restClient = restClientBuilder.baseUrl(mlService.getBaseUrl()).build();
        this.internalServiceToken = internalProperties.getServiceToken();
        this.internalServiceName = internalProperties.getServiceName();
        this.meterRegistry = meterRegistry;
    }
    public MlInferenceResponse requestPrediction(UUID deviceId, Instant timestamp, Map<String, Object> metrics) {
        return requestPrediction(deviceId, timestamp, metrics, List.of());
    }

    public MlInferenceResponse requestPrediction(
            UUID deviceId,
            Instant timestamp,
            Map<String, Object> metrics,
            List<MlExcludedInterval> excludedIntervals) {
        return requestPrediction(deviceId, timestamp, metrics, excludedIntervals, null, null, null);
    }

    public MlInferenceResponse requestPrediction(
            UUID deviceId,
            Instant timestamp,
            Map<String, Object> metrics,
            List<MlExcludedInterval> excludedIntervals,
            Map<String, Object> baseline,
            Map<String, Object> recentWindow,
            Map<String, Object> modelPreference) {
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
                    .body(new MlInferenceRequest(
                            deviceId,
                            timestamp,
                            metrics,
                            excludedIntervals,
                            baseline,
                            recentWindow,
                            modelPreference))
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
