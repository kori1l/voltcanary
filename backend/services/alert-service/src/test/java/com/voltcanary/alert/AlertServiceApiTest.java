package com.voltcanary.alert;

import com.voltcanary.alert.dto.AlertActionResponse;
import com.voltcanary.alert.dto.AlertPageResponse;
import com.voltcanary.alert.dto.AlertResponse;
import com.voltcanary.alert.repository.AlertEventRepository;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.service.AlertEscalationService;
import com.voltcanary.alert.service.AlertService;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
                "spring.flyway.enabled=true",
                "app.alert.authorization.enabled=false"
        })
class AlertServiceApiTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AlertService alertService;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AlertEscalationService alertEscalationService;

    @Test
    void anomalyCreatesDeduplicatedAlertAndApiCanAckAndResolve() {
        UUID deviceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID anomalyId = UUID.randomUUID();
        String ruleId = "power-spike-v2";
        Instant detectedAt = Instant.parse("2026-05-13T12:15:00Z");
        Instant windowStart = Instant.parse("2026-05-13T12:00:00Z");
        Instant windowEnd = Instant.parse("2026-05-13T12:15:00Z");
        AnomalyDetectedEvent event = new AnomalyDetectedEvent(
                eventId,
                deviceId,
                anomalyId,
                ruleId,
                windowStart,
                windowEnd,
                "input-hash-1",
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "A power spike was detected above the normal range.",
                detectedAt);

        alertService.handleAnomaly(event);
        alertService.handleAnomaly(event);

        ResponseEntity<List<AlertResponse>> listResponse = restTemplate.exchange(
                "/alerts?deviceId={deviceId}&status=OPEN&severity=WARNING&alertType=POWER_SPIKE&limit=10",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                },
                deviceId);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(1);

        AlertResponse alert = listResponse.getBody().getFirst();
        assertThat(alert.deviceId()).isEqualTo(deviceId);
        assertThat(alert.sourceEventId()).isEqualTo(eventId);
        assertThat(alert.anomalyId()).isEqualTo(anomalyId);
        assertThat(alert.ruleId()).isEqualTo(ruleId);
        assertThat(alert.calculationWindowStart()).isEqualTo(windowStart);
        assertThat(alert.calculationWindowEnd()).isEqualTo(windowEnd);
        assertThat(alert.calculationInputHash()).isEqualTo("input-hash-1");
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
        assertThat(alertEventRepository.findByAlertIdOrderByCreatedAtDesc(alert.id()))
                .hasSize(3)
                .allSatisfy(storedEvent -> {
                    assertThat(storedEvent.getPayloadJson()).contains("\"eventId\"");
                    assertThat(storedEvent.getPayloadJson()).contains("\"alertId\"");
                    assertThat(storedEvent.getPayloadJson()).contains("\"action\"");
                    assertThat(storedEvent.getPayloadJson()).contains("\"status\"");
                    assertThat(storedEvent.getPayloadJson()).contains("\"context\"");
                });
    }

    @Test
    void sourceEventIdIsIdempotentAcrossEquivalentEvents() {
        UUID deviceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AnomalyDetectedEvent event = new AnomalyDetectedEvent(
                eventId,
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "A power spike was detected above the normal range.",
                Instant.parse("2026-05-13T12:15:00Z"));

        var first = alertService.handleAnomaly(event);
        var second = alertService.handleAnomaly(new AnomalyDetectedEvent(
                eventId,
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "Duplicate source event.",
                Instant.parse("2026-05-13T12:45:00Z")));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getSourceEventId()).isEqualTo(eventId);
    }

    @Test
    void highAnalyticsSeverityIsPreservedAsHighAlertSeverity() {
        UUID deviceId = UUID.randomUUID();

        var alert = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "HIGH",
                75,
                "A high severity power spike was detected.",
                Instant.parse("2026-05-13T13:15:00Z")));

        assertThat(alert.getSeverity().name()).isEqualTo("HIGH");

        ResponseEntity<List<AlertResponse>> listResponse = restTemplate.exchange(
                "/alerts?deviceId={deviceId}&severity=HIGH&alertType=POWER_SPIKE&limit=10",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                },
                deviceId);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(1);
        assertThat(listResponse.getBody().getFirst().severity()).isEqualTo("HIGH");
    }

    @Test
    void resolvedAlertCreatesNewOpenAlertForRepeatedCondition() {
        UUID deviceId = UUID.randomUUID();
        Instant detectedAt = Instant.parse("2026-05-13T14:15:00Z");

        var first = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "Initial power spike.",
                detectedAt));
        alertService.resolve(first.getId(), "Investigated.", null, "ADMIN", "reopen-test");

        var reopened = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                31,
                "Power spike returned after resolution.",
                detectedAt.plusSeconds(600)));
        var repeatedWhileOpen = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                32,
                "Same unresolved condition.",
                detectedAt.plusSeconds(900)));

        assertThat(reopened.getId()).isNotEqualTo(first.getId());
        assertThat(reopened.getStatus().name()).isEqualTo("OPEN");
        assertThat(repeatedWhileOpen.getId()).isEqualTo(reopened.getId());

        ResponseEntity<List<AlertResponse>> listResponse = restTemplate.exchange(
                "/alerts?deviceId={deviceId}&alertType=POWER_SPIKE&limit=10",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                },
                deviceId);
        assertThat(listResponse.getBody())
                .filteredOn(alert -> deviceId.equals(alert.deviceId()))
                .extracting(AlertResponse::status)
                .containsExactlyInAnyOrder("OPEN", "RESOLVED");
    }

    @Test
    void differentHoursAndSeveritiesCreateSeparateAlerts() {
        UUID deviceId = UUID.randomUUID();
        Instant detectedAt = Instant.parse("2026-05-13T16:15:00Z");

        var first = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "Initial medium severity alert.",
                detectedAt));
        var nextHour = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                31,
                "Same condition in a later hour.",
                detectedAt.plusSeconds(3600)));
        var highSeverity = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "HIGH",
                80,
                "Higher severity in the original hour.",
                detectedAt.plusSeconds(300)));

        assertThat(nextHour.getId()).isNotEqualTo(first.getId());
        assertThat(highSeverity.getId()).isNotEqualTo(first.getId());
        assertThat(highSeverity.getSeverity().name()).isEqualTo("HIGH");
    }

    @Test
    void concurrentEquivalentEventsCreateOnlyOneAlert() throws Exception {
        UUID deviceId = UUID.randomUUID();
        Instant detectedAt = Instant.parse("2026-05-13T17:15:00Z");
        int workers = 8;
        var executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return alertService.handleAnomaly(new AnomalyDetectedEvent(
                            UUID.randomUUID(),
                            deviceId,
                            UUID.randomUUID(),
                            "CONCURRENT_POWER_SPIKE",
                            "MEDIUM",
                            30,
                            "Concurrent duplicate alert.",
                            detectedAt)).getId();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<UUID> alertIds = new ArrayList<>();
            for (Future<UUID> future : futures) {
                alertIds.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(alertIds).containsOnly(alertIds.getFirst());
            assertThat(alertRepository.findAll())
                    .filteredOn(alert -> deviceId.equals(alert.getDeviceId()))
                    .filteredOn(alert -> "CONCURRENT_POWER_SPIKE".equals(alert.getAlertType()))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void escalationCreatesSingleEventForUnacknowledgedDueAlerts() {
        UUID deviceId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-05-13T18:00:00Z");
        Instant scanTime = createdAt.plusSeconds(31 * 60L);
        var due = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "Unacknowledged warning alert.",
                createdAt));
        var acknowledged = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "POWER_SPIKE",
                "CRITICAL",
                95,
                "Acknowledged critical alert.",
                createdAt));
        alertService.acknowledge(acknowledged.getId(), null, "ADMIN", "escalation-test");

        int escalated = alertEscalationService.escalateDueAlerts(scanTime);
        int repeated = alertEscalationService.escalateDueAlerts(scanTime.plusSeconds(60));

        assertThat(escalated).isPositive();
        assertThat(repeated).isZero();
        assertThat(alertEventRepository.findByAlertIdOrderByCreatedAtDesc(due.getId()))
                .filteredOn(event -> "ESCALATED".equals(event.getEventType()))
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.getPayloadJson())
                        .contains("\"action\":\"ESCALATED\"")
                        .contains("\"thresholdSeconds\":1800"));
        assertThat(alertEventRepository.findByAlertIdOrderByCreatedAtDesc(acknowledged.getId()))
                .filteredOn(event -> "ESCALATED".equals(event.getEventType()))
                .isEmpty();
    }

    @Test
    void alertsPageReturnsFilteredPaginationMetadata() {
        UUID deviceId = UUID.randomUUID();
        for (int index = 0; index < 3; index++) {
            alertService.handleAnomaly(new AnomalyDetectedEvent(
                    UUID.randomUUID(),
                    deviceId,
                    UUID.randomUUID(),
                    "PAGE_TEST",
                    "MEDIUM",
                    10 + index,
                    "Page test alert " + index,
                    Instant.parse("2026-05-13T15:15:00Z").plusSeconds(index * 3600L)));
        }

        ResponseEntity<AlertPageResponse> firstPage = restTemplate.getForEntity(
                "/alerts/page?deviceId={deviceId}&alertType=PAGE_TEST&page=0&size=2",
                AlertPageResponse.class,
                deviceId);
        ResponseEntity<AlertPageResponse> secondPage = restTemplate.getForEntity(
                "/alerts/page?deviceId={deviceId}&alertType=PAGE_TEST&page=1&size=2",
                AlertPageResponse.class,
                deviceId);

        assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstPage.getBody().content()).hasSize(2);
        assertThat(firstPage.getBody().page()).isZero();
        assertThat(firstPage.getBody().size()).isEqualTo(2);
        assertThat(firstPage.getBody().totalElements()).isEqualTo(3);
        assertThat(firstPage.getBody().totalPages()).isEqualTo(2);
        assertThat(firstPage.getBody().hasNext()).isTrue();
        assertThat(firstPage.getBody().content())
                .extracting(AlertResponse::description)
                .containsExactly("Page test alert 2", "Page test alert 1");

        assertThat(secondPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondPage.getBody().content()).hasSize(1);
        assertThat(secondPage.getBody().page()).isEqualTo(1);
        assertThat(secondPage.getBody().totalElements()).isEqualTo(3);
        assertThat(secondPage.getBody().totalPages()).isEqualTo(2);
        assertThat(secondPage.getBody().hasNext()).isFalse();
        assertThat(secondPage.getBody().content().getFirst().description()).isEqualTo("Page test alert 0");
    }
}
