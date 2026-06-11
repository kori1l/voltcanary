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
}
