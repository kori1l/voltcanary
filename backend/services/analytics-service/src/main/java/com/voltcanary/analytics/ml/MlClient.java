package com.voltcanary.analytics.ml;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MlClient {

    private static final Logger log = LoggerFactory.getLogger(MlClient.class);

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final String internalServiceToken;
    private final String internalServiceName;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Autowired
    public MlClient(
            RestClient.Builder restClientBuilder,
            AnalyticsDownstreamProperties downstreamProperties,
            AnalyticsInternalProperties internalProperties,
            MeterRegistry meterRegistry) {
        this(
                buildRestClient(restClientBuilder, downstreamProperties),
                internalProperties,
                meterRegistry);
    }

    public MlClient(RestClient restClient, AnalyticsInternalProperties internalProperties, MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.internalServiceToken = internalProperties.getServiceToken();
        this.internalServiceName = internalProperties.getServiceName();
        this.meterRegistry = meterRegistry;
    }

    private static RestClient buildRestClient(
            RestClient.Builder restClientBuilder,
            AnalyticsDownstreamProperties downstreamProperties) {
        AnalyticsDownstreamProperties.DownstreamService mlService = downstreamProperties.getMlService();
        return restClientBuilder
                .requestFactory(new SimpleClientHttpRequestFactory())
                .baseUrl(mlService.getBaseUrl())
                .build();
    }
    public MlInferenceResponse requestPrediction(UUID deviceId, Instant timestamp, Map<String, Object> metrics) {
        return requestPrediction(deviceId, timestamp, metrics, List.of());
    }

    public MlTrainingResponse requestTraining(MlTrainingRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String body = objectMapper.writeValueAsString(request);
            MlTrainingResponse response = restClient.post()
                    .uri("/train")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.set("X-Source-Service", internalServiceName);
                        if (!internalServiceToken.isBlank()) {
                            headers.set("X-Internal-Service-Token", internalServiceToken);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(MlTrainingResponse.class);
            sample.stop(meterRegistry.timer("analytics.ml.training.worker.request.duration", "status", "success"));
            return response;
        } catch (JsonProcessingException exception) {
            meterRegistry.counter("analytics.ml.training.worker.request.failures", "reason", exception.getClass().getSimpleName()).increment();
            sample.stop(meterRegistry.timer("analytics.ml.training.worker.request.duration", "status", "failure"));
            log.warn("mlTrainingRequestFailed datasetVersionId={} reason={} phase=serialize", request.datasetVersionId(), exception.getClass().getSimpleName());
            return null;
        } catch (RestClientException exception) {
            meterRegistry.counter("analytics.ml.training.worker.request.failures", "reason", exception.getClass().getSimpleName()).increment();
            sample.stop(meterRegistry.timer("analytics.ml.training.worker.request.duration", "status", "failure"));
            log.warn("mlTrainingRequestFailed datasetVersionId={} reason={} phase=request", request.datasetVersionId(), exception.getClass().getSimpleName());
            return null;
        }
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
            String body = objectMapper.writeValueAsString(new MlInferenceRequest(
                    deviceId,
                    timestamp,
                    metrics,
                    excludedIntervals,
                    baseline,
                    recentWindow,
                    modelPreference));
            MlInferenceResponse response = restClient.post()
                    .uri("/inference")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.set("X-Source-Service", internalServiceName);
                        if (!internalServiceToken.isBlank()) {
                            headers.set("X-Internal-Service-Token", internalServiceToken);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(MlInferenceResponse.class);
            sample.stop(meterRegistry.timer(
                    "analytics.ml.inference.duration",
                    "status",
                    response == null || response.predictionStatus() == null ? "success" : response.predictionStatus(),
                    "modelVersionId",
                    modelVersionId(modelPreference, response)));
            return response;
        } catch (JsonProcessingException exception) {
            meterRegistry.counter(
                    "analytics.ml.inference.failures",
                    "reason",
                    exception.getClass().getSimpleName(),
                    "modelVersionId",
                    modelVersionId(modelPreference, null)).increment();
            sample.stop(meterRegistry.timer(
                    "analytics.ml.inference.duration",
                    "status",
                    "failure",
                    "modelVersionId",
                    modelVersionId(modelPreference, null)));
            log.warn("mlInferenceFailed deviceId={} reason={} phase=serialize", deviceId, exception.getClass().getSimpleName());
            return null;
        } catch (RestClientException exception) {
            meterRegistry.counter(
                    "analytics.ml.inference.failures",
                    "reason",
                    exception.getClass().getSimpleName(),
                    "modelVersionId",
                    modelVersionId(modelPreference, null)).increment();
            sample.stop(meterRegistry.timer(
                    "analytics.ml.inference.duration",
                    "status",
                    "failure",
                    "modelVersionId",
                    modelVersionId(modelPreference, null)));
            log.warn("mlInferenceFailed deviceId={} reason={} phase=request", deviceId, exception.getClass().getSimpleName());
            return null;
        }
    }

    private String modelVersionId(Map<String, Object> modelPreference, MlInferenceResponse response) {
        if (response != null && response.modelVersionId() != null && !response.modelVersionId().isBlank()) {
            return response.modelVersionId();
        }
        Object value = modelPreference == null ? null : modelPreference.get("modelVersionId");
        String result = value == null ? "" : value.toString();
        return result.isBlank() ? "none" : result;
    }
}
