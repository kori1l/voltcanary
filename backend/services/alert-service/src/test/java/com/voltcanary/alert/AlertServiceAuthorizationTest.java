package com.voltcanary.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voltcanary.alert.dto.AlertActionResponse;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.dto.AlertSummaryResponse;
import com.voltcanary.alert.service.AlertAccessService;
import com.voltcanary.alert.service.AlertService;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:alert-auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=true",
                "app.alert.notifications.worker-enabled=false",
                "app.alert.reconciliation.enabled=false"
        })
class AlertServiceAuthorizationTest {

    private static final String USER_ID = "7f59fc77-2d41-4df6-9ad2-5063dc58adf4";
    private static final String ROLE = "USER";
    private static final String CORRELATION_ID = "alert-auth-test";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AlertService alertService;

    @Autowired
    private AlertAccessService accessService;

    private UUID ownedDeviceId;
    private UUID foreignDeviceId;
    private UUID ownedAccountId;
    private UUID foreignAccountId;

    @BeforeEach
    void setUp() {
        ownedDeviceId = UUID.randomUUID();
        foreignDeviceId = UUID.randomUUID();
        ownedAccountId = UUID.randomUUID();
        foreignAccountId = UUID.randomUUID();
        when(accessService.canReadDevice(eq(ownedDeviceId), eq(USER_ID), eq(ROLE), eq(CORRELATION_ID))).thenReturn(true);
        when(accessService.canReadDevice(eq(foreignDeviceId), eq(USER_ID), eq(ROLE), eq(CORRELATION_ID))).thenReturn(false);
        when(accessService.resolveDeviceAccountId(eq(ownedDeviceId), anyString())).thenReturn(ownedAccountId);
        when(accessService.resolveDeviceAccountId(eq(foreignDeviceId), anyString())).thenReturn(foreignAccountId);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"))
                .when(accessService)
                .requireReadAccess(eq(foreignDeviceId), eq(USER_ID), eq(ROLE), eq(CORRELATION_ID));
    }

    @Test
    void listFiltersOutForeignDeviceAlerts() {
        AlertResponse owned = createAlert(ownedDeviceId, "POWER_SPIKE");
        createAlert(foreignDeviceId, "LONG_CYCLE");

        ResponseEntity<List<AlertResponse>> response = restTemplate.exchange(
                "/alerts",
                HttpMethod.GET,
                userEntity(null),
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(AlertResponse::id).contains(owned.id());
        assertThat(response.getBody()).extracting(AlertResponse::deviceId).doesNotContain(foreignDeviceId);
    }

    @Test
    void createdAlertsStoreResolvedAccountId() {
        AlertResponse owned = createAlert(ownedDeviceId, "POWER_SPIKE");

        assertThat(owned.accountId()).isEqualTo(ownedAccountId);
    }

    @Test
    void actionsRejectForeignDeviceAlerts() {
        AlertResponse foreign = createAlert(foreignDeviceId, "LONG_CYCLE");

        ResponseEntity<AlertResponse> getResponse = restTemplate.exchange(
                "/alerts/{id}",
                HttpMethod.GET,
                userEntity(null),
                AlertResponse.class,
                foreign.id());
        ResponseEntity<AlertActionResponse> ackResponse = restTemplate.exchange(
                "/alerts/{id}/ack",
                HttpMethod.POST,
                userEntity(null),
                AlertActionResponse.class,
                foreign.id());
        ResponseEntity<AlertActionResponse> resolveResponse = restTemplate.exchange(
                "/alerts/{id}/resolve",
                HttpMethod.POST,
                userEntity(Map.of("reason", "foreign")),
                AlertActionResponse.class,
                foreign.id());

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ackResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resolveResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void actionsStoreLifecycleActor() {
        AlertResponse owned = createAlert(ownedDeviceId, "POWER_SPIKE");

        ResponseEntity<AlertActionResponse> ackResponse = restTemplate.exchange(
                "/alerts/{id}/ack",
                HttpMethod.POST,
                userEntity(null),
                AlertActionResponse.class,
                owned.id());
        ResponseEntity<AlertActionResponse> resolveResponse = restTemplate.exchange(
                "/alerts/{id}/resolve",
                HttpMethod.POST,
                userEntity(Map.of("reason", "handled")),
                AlertActionResponse.class,
                owned.id());
        ResponseEntity<AlertResponse> getResponse = restTemplate.exchange(
                "/alerts/{id}",
                HttpMethod.GET,
                userEntity(null),
                AlertResponse.class,
                owned.id());

        assertThat(ackResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().acknowledgedBy()).isEqualTo(UUID.fromString(USER_ID));
        assertThat(getResponse.getBody().resolvedBy()).isEqualTo(UUID.fromString(USER_ID));
    }

    @Test
    void summaryCountsOnlyAccessibleAlerts() {
        AlertResponse open = createAlert(ownedDeviceId, "POWER_SPIKE");
        AlertResponse acknowledged = createAlert(ownedDeviceId, "CONSUMPTION_GROWTH");
        createAlert(foreignDeviceId, "LONG_CYCLE");
        restTemplate.exchange(
                "/alerts/{id}/ack",
                HttpMethod.POST,
                userEntity(null),
                AlertActionResponse.class,
                acknowledged.id());

        ResponseEntity<AlertSummaryResponse> response = restTemplate.exchange(
                "/alerts/summary?deviceId={deviceId}",
                HttpMethod.GET,
                userEntity(null),
                AlertSummaryResponse.class,
                ownedDeviceId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().total()).isEqualTo(2);
        assertThat(response.getBody().byStatus()).containsEntry("OPEN", 1L);
        assertThat(response.getBody().byStatus()).containsEntry("ACKNOWLEDGED", 1L);
        assertThat(response.getBody().bySeverity()).containsEntry("WARNING", 2L);
        assertThat(response.getBody().byDevice()).containsEntry(open.deviceId().toString(), 2L);
        assertThat(response.getBody().byDevice()).doesNotContainKey(foreignDeviceId.toString());
    }

    private AlertResponse createAlert(UUID deviceId, String type) {
        var alert = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                type,
                "MEDIUM",
                30,
                "Alert authorization test.",
                Instant.now()));
        return new AlertResponse(
                alert.getId(),
                alert.getDeviceId(),
                alert.getAccountId(),
                alert.getSourceEventId(),
                alert.getAnomalyId(),
                alert.getRiskScoreId(),
                alert.getRuleId(),
                alert.getCalculationWindowStart(),
                alert.getCalculationWindowEnd(),
                alert.getCalculationInputHash(),
                alert.getAlertType(),
                alert.getTitle(),
                alert.getDescription(),
                alert.getSeverity().name(),
                alert.getStatus().name(),
                alert.getCreatedAt(),
                alert.getAcknowledgedAt(),
                alert.getAcknowledgedBy(),
                alert.getResolvedAt(),
                alert.getResolvedBy(),
                alert.getAssignedTo(),
                alert.getResolutionReason());
    }

    private HttpEntity<Object> userEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", USER_ID);
        headers.set("X-User-Role", ROLE);
        headers.set("X-Correlation-Id", CORRELATION_ID);
        return new HttpEntity<>(body, headers);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        AlertAccessService alertAccessService() {
            return mock(AlertAccessService.class);
        }
    }
}
