package com.voltcanary.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.config.AnalyticsAuthorizationProperties;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.AnalyticsInternalProperties;
import com.voltcanary.analytics.config.AnalyticsKafkaProperties;
import com.voltcanary.analytics.consumer.TelemetryValidatedConsumer;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.domain.RiskScore;
import com.voltcanary.analytics.ml.MlClient;
import com.voltcanary.analytics.ml.MlTrainingMetricRow;
import com.voltcanary.analytics.ml.MlTrainingRequest;
import com.voltcanary.analytics.ml.MlInferenceRequest;
import com.voltcanary.analytics.producer.AnalyticsDeadLetterPublisher;
import com.voltcanary.analytics.producer.AnalyticsEventPublisher;
import com.voltcanary.analytics.service.AnalyticsAccessService;
import com.voltcanary.analytics.service.TelemetryValidatedEventHandler;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import com.voltcanary.common.events.analytics.RiskScoreChangedEvent;
import com.voltcanary.common.events.telemetry.TelemetryValidatedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AnalyticsPipelineTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void telemetryValidatedEventEnqueuesRecalculationJob() throws Exception {
        TelemetryValidatedEventHandler eventHandler = mock(TelemetryValidatedEventHandler.class);
        AnalyticsDeadLetterPublisher deadLetterPublisher = mock(AnalyticsDeadLetterPublisher.class);
        TelemetryValidatedConsumer consumer = new TelemetryValidatedConsumer(objectMapper, eventHandler, deadLetterPublisher);
        UUID deviceId = UUID.randomUUID();
        TelemetryValidatedEvent event = new TelemetryValidatedEvent(
                UUID.randomUUID(),
                deviceId,
                "source-1",
                Instant.parse("2026-05-29T10:00:00Z"),
                Map.of("activePowerW", 120),
                List.of(),
                Instant.parse("2026-05-29T10:00:01Z"));

        consumer.consume(objectMapper.writeValueAsString(event));

        verify(eventHandler).handle(event);
    }

    @Test
    void invalidTelemetryValidatedEventIsRejected() {
        TelemetryValidatedEventHandler eventHandler = mock(TelemetryValidatedEventHandler.class);
        AnalyticsDeadLetterPublisher deadLetterPublisher = mock(AnalyticsDeadLetterPublisher.class);
        TelemetryValidatedConsumer consumer = new TelemetryValidatedConsumer(objectMapper, eventHandler, deadLetterPublisher);

        assertThatThrownBy(() -> consumer.consume("{not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid TelemetryValidatedEvent payload");
        verify(deadLetterPublisher).publish(
                eq("telemetry.validated.v1"),
                eq("{not-json"),
                eq("Invalid TelemetryValidatedEvent payload"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyticsPublisherUsesExpectedEventPayloads() {
        KafkaTemplate<String, AnomalyDetectedEvent> anomalyTemplate = mock(KafkaTemplate.class);
        KafkaTemplate<String, RiskScoreChangedEvent> riskTemplate = mock(KafkaTemplate.class);
        AnalyticsKafkaProperties kafkaProperties = new AnalyticsKafkaProperties();
        kafkaProperties.setPublishEnabled(true);
        AnalyticsEventPublisher publisher = new AnalyticsEventPublisher(anomalyTemplate, riskTemplate, kafkaProperties);
        UUID deviceId = UUID.randomUUID();
        Anomaly anomaly = new Anomaly();
        anomaly.setId(UUID.randomUUID());
        anomaly.setDeviceId(deviceId);
        anomaly.setType("POWER_SPIKE");
        anomaly.setSeverity("MEDIUM");
        anomaly.setScore(30);
        anomaly.setDescription("Spike");
        anomaly.setDetectedAt(Instant.parse("2026-05-29T10:00:00Z"));
        RiskScore risk = new RiskScore();
        risk.setId(UUID.randomUUID());
        risk.setDeviceId(deviceId);
        risk.setScore(60);
        risk.setLevel("MEDIUM");
        risk.setCalculatedAt(Instant.parse("2026-05-29T10:01:00Z"));

        publisher.publishAnomaly(anomaly);
        publisher.publishRiskScore(risk, 20);

        ArgumentCaptor<AnomalyDetectedEvent> anomalyEvent = ArgumentCaptor.forClass(AnomalyDetectedEvent.class);
        verify(anomalyTemplate).send(eq("anomaly.detected.v1"), eq(deviceId.toString()), anomalyEvent.capture());
        assertThat(anomalyEvent.getValue().deviceId()).isEqualTo(deviceId);
        assertThat(anomalyEvent.getValue().type()).isEqualTo("POWER_SPIKE");
        assertThat(anomalyEvent.getValue().severity()).isEqualTo("MEDIUM");

        ArgumentCaptor<RiskScoreChangedEvent> riskEvent = ArgumentCaptor.forClass(RiskScoreChangedEvent.class);
        verify(riskTemplate).send(eq("risk-score.changed.v1"), eq(deviceId.toString()), riskEvent.capture());
        assertThat(riskEvent.getValue().previousScore()).isEqualTo(20);
        assertThat(riskEvent.getValue().currentScore()).isEqualTo(60);
        assertThat(riskEvent.getValue().riskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void analyticsAuthorizationRejectsForeignDevice() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsAuthorizationProperties properties = new AnalyticsAuthorizationProperties();
        properties.setDeviceServiceBaseUrl("http://device-service");
        properties.setTimeout(Duration.ofSeconds(1));
        AnalyticsInternalProperties internalProperties = new AnalyticsInternalProperties();
        internalProperties.setServiceToken("internal-token");
        AnalyticsAccessService accessService = new AnalyticsAccessService(
                properties,
                builder,
                internalProperties);
        UUID deviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        server.expect(once(), requestTo("http://device-service/internal/devices/" + deviceId + "/access?userId=" + userId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"allowed\":false}", org.springframework.http.MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> accessService.requireReadAccess(deviceId, userId.toString(), "USER", "corr-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        server.verify();
    }

    @Test
    void telemetryClientMapsServerFailureToServiceUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsDownstreamProperties downstreamProperties = new AnalyticsDownstreamProperties();
        downstreamProperties.setTelemetryService(new AnalyticsDownstreamProperties.DownstreamService(
                "http://telemetry-service",
                Duration.ofSeconds(1)));
        TelemetryClient client = new TelemetryClient(
                builder,
                downstreamProperties,
                new AnalyticsInternalProperties(),
                new SimpleMeterRegistry());
        UUID deviceId = UUID.randomUUID();
        server.expect(once(), requestTo(startsWith("http://telemetry-service/telemetry/devices/" + deviceId + "/readings")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.readings(
                deviceId,
                "activePowerW",
                Instant.parse("2026-05-29T00:00:00Z"),
                Instant.parse("2026-05-29T01:00:00Z")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        server.verify();
    }

    @Test
    void mlClientReturnsNullWhenInferenceIsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsDownstreamProperties downstreamProperties = new AnalyticsDownstreamProperties();
        downstreamProperties.setMlService(new AnalyticsDownstreamProperties.DownstreamService(
                "http://ml-service",
                Duration.ofSeconds(1)));
        MlClient client = new MlClient(
                builder,
                downstreamProperties,
                new AnalyticsInternalProperties(),
                new SimpleMeterRegistry());
        server.expect(once(), requestTo("http://ml-service/inference"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThat(client.requestPrediction(UUID.randomUUID(), Instant.now(), Map.of("metric", "activePowerW")))
                .isNull();
        server.verify();
    }

    @Test
    void mlClientSendsInternalBoundaryHeadersForInference() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsInternalProperties internalProperties = new AnalyticsInternalProperties();
        internalProperties.setServiceName("analytics-service");
        internalProperties.setServiceToken("test-internal-token");
        AnalyticsDownstreamProperties downstreamProperties = new AnalyticsDownstreamProperties();
        downstreamProperties.setMlService(new AnalyticsDownstreamProperties.DownstreamService(
                "http://ml-service",
                Duration.ofSeconds(1)));
        MlClient client = new MlClient(
                builder,
                downstreamProperties,
                internalProperties,
                new SimpleMeterRegistry());
        UUID deviceId = UUID.randomUUID();
        server.expect(once(), requestTo("http://ml-service/inference"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Source-Service", "analytics-service"))
                .andExpect(header("X-Internal-Service-Token", "test-internal-token"))
                .andRespond(withSuccess("""
                        {
                          "deviceId": "%s",
                          "modelName": "voltcanary-baseline-heuristic",
                          "modelVersion": "baseline-heuristic-v1",
                          "modelType": "baseline_heuristic",
                          "modelStatus": "READY_HEURISTIC",
                          "predictionStatus": "BASELINE_HEURISTIC",
                          "anomalyScore": 0.0,
                          "degradationScore": 0.0,
                          "riskLevel": "LOW",
                          "confidence": 0.9,
                          "signals": [],
                          "explanations": [],
                          "recommendedActions": [],
                          "inputHash": "sha256:test"
                        }
                        """.formatted(deviceId), MediaType.APPLICATION_JSON));

        assertThat(client.requestPrediction(deviceId, Instant.now(), Map.of("metric", "activePowerW")))
                .isNotNull();
        server.verify();
    }

    @Test
    void mlClientSendsInternalBoundaryHeadersForTraining() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsInternalProperties internalProperties = new AnalyticsInternalProperties();
        internalProperties.setServiceName("analytics-service");
        internalProperties.setServiceToken("test-internal-token");
        AnalyticsDownstreamProperties downstreamProperties = new AnalyticsDownstreamProperties();
        downstreamProperties.setMlService(new AnalyticsDownstreamProperties.DownstreamService(
                "http://ml-service",
                Duration.ofSeconds(1)));
        MlClient client = new MlClient(
                builder,
                downstreamProperties,
                internalProperties,
                new SimpleMeterRegistry());
        UUID datasetVersionId = UUID.randomUUID();
        UUID trainingRunId = UUID.randomUUID();
        MlTrainingRequest request = new MlTrainingRequest(
                trainingRunId,
                "ml-training:" + trainingRunId,
                "model-" + trainingRunId,
                datasetVersionId,
                UUID.randomUUID(),
                "INTERNAL_TELEMETRY",
                "ANOMALY_DETECTION",
                "baseline-statistical-anomaly-v1",
                null,
                null,
                null,
                null,
                List.of(new MlTrainingMetricRow(
                        Instant.parse("2026-07-30T12:00:00Z"),
                        "device-1",
                        "activePowerW",
                        850.0,
                        Map.of())));
        server.expect(once(), requestTo("http://ml-service/train"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Source-Service", "analytics-service"))
                .andExpect(header("X-Internal-Service-Token", "test-internal-token"))
                .andRespond(withSuccess("""
                        {
                          "runId": "run-1",
                          "status": "SUCCEEDED",
                          "datasetVersionId": "%s",
                          "modelVersionId": "model-v1",
                          "modelVersion": "v1",
                          "artifactUri": "artifact://local/model-v1/model.joblib",
                          "manifestUri": "artifact://local/model-v1/manifest.json",
                          "artifactHash": "sha256:test",
                          "artifactSizeBytes": 1,
                          "metrics": {}
                        }
                        """.formatted(datasetVersionId), MediaType.APPLICATION_JSON));

        assertThat(client.requestTraining(request)).isNotNull();
        server.verify();
    }

    @Test
    void mlInferenceRequestIncludesOnlineFeatureVectorAndExplicitMissingFeatureErrors() {
        UUID deviceId = UUID.randomUUID();
        MlInferenceRequest ready = new MlInferenceRequest(
                deviceId,
                Instant.parse("2026-07-30T12:00:00Z"),
                Map.of(
                        "metric", "activePowerW",
                        "latestValue", 120.0,
                        "mean", 100.0,
                        "min", 90.0,
                        "max", 130.0,
                        "deviceType", "washer"),
                List.of(),
                Map.of("mean", 100.0),
                Map.of("sampleCount", 6),
                Map.of("requiredFeatureSchemaHash", "sha256:model", "modelFamily", "ANOMALY_DETECTION"));

        assertThat(ready.featureGenerationStatus()).isEqualTo("READY");
        assertThat(ready.featureVector())
                .containsEntry("deviceKey", deviceId.toString())
                .containsEntry("metricName", "activePowerW")
                .containsEntry("baselineDeviationRatio", 1.2)
                .containsEntry("applianceLoadRatio", 1.2)
                .containsEntry("applianceActivityFlag", true)
                .containsEntry("deviceType", "washer");
        assertThat(ready.featureContract())
                .containsEntry("requiredFeatureSchemaHash", "sha256:model")
                .containsKey("featureSchemaHash");

        MlInferenceRequest unavailable = new MlInferenceRequest(
                deviceId,
                Instant.parse("2026-07-30T12:00:00Z"),
                Map.of(),
                List.of(),
                Map.of(),
                Map.of("sampleCount", 0),
                Map.of());

        assertThat(unavailable.featureGenerationStatus()).isEqualTo("FEATURES_UNAVAILABLE");
        assertThat(unavailable.featureGenerationErrors())
                .contains("MISSING_REQUIRED_FEATURE: metricName", "MISSING_REQUIRED_FEATURE: latestValue");
    }

    @Test
    void mlInferenceRequestTreatsPrecomputedFeatureRowsAsReadyFeatureVectors() {
        UUID deviceId = UUID.randomUUID();
        MlInferenceRequest request = new MlInferenceRequest(
                deviceId,
                Instant.parse("2026-07-30T12:00:00Z"),
                Map.of(
                        "rollingMean", 830.0,
                        "baselineDeviationRatio", 1.18,
                        "samplingGapSeconds", 14400.0,
                        "applianceLoadRatio", 0.92),
                List.of(),
                Map.of(),
                Map.of("sampleCount", 169),
                Map.of("modelFamily", "FAILURE_PREDICTION"));

        assertThat(request.featureGenerationStatus()).isEqualTo("READY");
        assertThat(request.featureGenerationErrors()).isEmpty();
        assertThat(request.featureVector())
                .containsEntry("rollingMean", 830.0)
                .containsEntry("baselineDeviationRatio", 1.18)
                .containsEntry("applianceLoadRatio", 0.92);
        assertThat(request.featureContract()).containsEntry("modelFamily", "FAILURE_PREDICTION");
    }

    @Test
    void mlInferenceRequestReportsExplicitContractMetadataMismatches() {
        UUID deviceId = UUID.randomUUID();
        MlInferenceRequest request = new MlInferenceRequest(
                deviceId,
                Instant.parse("2026-07-30T12:00:00Z"),
                Map.of(
                        "metric", "activePowerW",
                        "latestValue", 120.0,
                        "mean", 100.0,
                        "min", 90.0,
                        "max", 130.0,
                        "featureSetVersion", "feature-set-v2",
                        "featureGeneratorVersion", "analytics-feature-pipeline-v2",
                        "deviceType", "washer"),
                List.of(),
                Map.of("mean", 100.0),
                Map.of("sampleCount", 6),
                Map.of(
                        "modelFamily", "FAILURE_PREDICTION",
                        "requiredFeatureContractVersion", "feature-vector-contract-v1",
                        "requiredFeatureSetVersion", "feature-set-v1",
                        "requiredFeatureGeneratorVersion", "analytics-feature-pipeline-v1",
                        "requiredFeatureTemplateId", "generic-appliance",
                        "requiredFeatureTemplateVersion", "template-v1",
                        "requiredHorizonDays", 14,
                        "requiredFeatureContractMetadataHash", "sha256:trained-contract"));

        assertThat(request.featureGenerationStatus()).isEqualTo("FEATURE_SCHEMA_MISMATCH");
        assertThat(request.featureGenerationErrors())
                .anySatisfy(error -> assertThat(error).startsWith("FEATURE_SET_VERSION_MISMATCH"))
                .anySatisfy(error -> assertThat(error).startsWith("FEATURE_GENERATOR_VERSION_MISMATCH"))
                .anySatisfy(error -> assertThat(error).startsWith("FEATURE_HORIZON_MISMATCH"))
                .anySatisfy(error -> assertThat(error).startsWith("FEATURE_SCHEMA_MISMATCH"));
        assertThat(request.featureContract())
                .containsEntry("requiredFeatureContractMetadataHash", "sha256:trained-contract")
                .containsKey("contractMetadataHash");
    }
}
