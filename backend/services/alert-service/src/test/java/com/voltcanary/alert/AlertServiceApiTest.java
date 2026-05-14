package com.voltcanary.alert;

import com.voltcanary.alert.dto.AlertActionResponse;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.service.AlertService;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:alert-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=true"
        })
class AlertServiceApiTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AlertService alertService;

    @Test
    void anomalyCreatesDeduplicatedAlertAndApiCanAckAndResolve() {
        UUID deviceId = UUID.randomUUID();
        Instant detectedAt = Instant.parse("2026-05-13T12:15:00Z");
        AnomalyDetectedEvent event = new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "A power spike was detected above the normal range.",
                detectedAt);

        alertService.handleAnomaly(event);
        alertService.handleAnomaly(event);

        ResponseEntity<List<AlertResponse>> listResponse = restTemplate.exchange(
                "/alerts",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                });
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(1);

        AlertResponse alert = listResponse.getBody().getFirst();
        assertThat(alert.deviceId()).isEqualTo(deviceId);
        assertThat(alert.alertType()).isEqualTo("POWER_SPIKE");
        assertThat(alert.status()).isEqualTo("OPEN");

        ResponseEntity<AlertActionResponse> ackResponse = restTemplate.postForEntity(
                "/alerts/{id}/ack",
                null,
                AlertActionResponse.class,
                alert.id());
        assertThat(ackResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ackResponse.getBody().status()).isEqualTo("ACKNOWLEDGED");

        ResponseEntity<AlertActionResponse> resolveResponse = restTemplate.postForEntity(
                "/alerts/{id}/resolve",
                new HttpEntity<>(Map.of("reason", "Breaker was inspected and load returned to normal.")),
                AlertActionResponse.class,
                alert.id());
        assertThat(resolveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolveResponse.getBody().status()).isEqualTo("RESOLVED");

        ResponseEntity<AlertResponse> getResponse = restTemplate.getForEntity(
                "/alerts/{id}",
                AlertResponse.class,
                alert.id());
        assertThat(getResponse.getBody().status()).isEqualTo("RESOLVED");
        assertThat(getResponse.getBody().resolvedAt()).isNotNull();
        assertThat(getResponse.getBody().resolutionReason()).isEqualTo("Breaker was inspected and load returned to normal.");
    }
}
