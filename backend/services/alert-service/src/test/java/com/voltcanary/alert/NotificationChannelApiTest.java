package com.voltcanary.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.voltcanary.alert.domain.Alert;
import com.voltcanary.alert.domain.AlertSeverity;
import com.voltcanary.alert.domain.AlertStatus;
import com.voltcanary.alert.dto.NotificationAttemptResponse;
import com.voltcanary.alert.dto.NotificationChannelResponse;
import com.voltcanary.alert.repository.AlertRepository;
import com.voltcanary.alert.repository.NotificationChannelRepository;
import com.voltcanary.alert.service.AlertService;
import com.voltcanary.alert.service.NotificationDeliveryService;
import com.voltcanary.alert.service.NotificationAttemptService;
import com.voltcanary.common.events.analytics.AnomalyDetectedEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.kafka.consume-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:notification-channel-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=true",
                "app.alert.authorization.enabled=false"
        })
class NotificationChannelApiTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AlertService alertService;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private NotificationAttemptService notificationAttemptService;

    @Autowired
    private NotificationDeliveryService notificationDeliveryService;

    @Autowired
    private NotificationChannelRepository notificationChannelRepository;

    private HttpServer webhookServer;

    @AfterEach
    void tearDown() {
        if (webhookServer != null) {
            webhookServer.stop(0);
        }
    }

    @Test
    void channelCrudIsScopedByAccountAndUsesCredentialsRef() {
        ResponseEntity<NotificationChannelResponse> createResponse = restTemplate.exchange(
                "/alerts/notification-channels",
                HttpMethod.POST,
                entity(ACCOUNT_ID, Map.of(
                        "name", "Ops webhook",
                        "channelType", "webhook",
                        "config", Map.of("urlRef", "ops-primary-webhook", "headers", Map.of("X-Team", "ops")),
                        "credentialsRef", "vault://alerts/webhooks/ops",
                        "enabled", true)),
                NotificationChannelResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        NotificationChannelResponse created = createResponse.getBody();
        assertThat(created.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(created.channelType()).isEqualTo("WEBHOOK");
        assertThat(created.credentialsRef()).isEqualTo("vault://alerts/webhooks/ops");
        assertThat(created.config()).containsEntry("urlRef", "ops-primary-webhook");
        assertThat(created.enabled()).isTrue();

        ResponseEntity<List<NotificationChannelResponse>> listResponse = restTemplate.exchange(
                "/alerts/notification-channels",
                HttpMethod.GET,
                entity(ACCOUNT_ID, null),
                new ParameterizedTypeReference<>() {
                });
        ResponseEntity<List<NotificationChannelResponse>> otherAccountListResponse = restTemplate.exchange(
                "/alerts/notification-channels",
                HttpMethod.GET,
                entity(OTHER_ACCOUNT_ID, null),
                new ParameterizedTypeReference<>() {
                });

        assertThat(listResponse.getBody()).extracting(NotificationChannelResponse::id).contains(created.id());
        assertThat(otherAccountListResponse.getBody()).isEmpty();

        ResponseEntity<NotificationChannelResponse> disableResponse = restTemplate.exchange(
                "/alerts/notification-channels/{id}/disable",
                HttpMethod.POST,
                entity(ACCOUNT_ID, null),
                NotificationChannelResponse.class,
                created.id());
        assertThat(disableResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disableResponse.getBody().enabled()).isFalse();

        ResponseEntity<NotificationChannelResponse> updateResponse = restTemplate.exchange(
                "/alerts/notification-channels/{id}",
                HttpMethod.PUT,
                entity(ACCOUNT_ID, Map.of(
                        "name", "Ops webhook updated",
                        "config", Map.of("urlRef", "ops-secondary-webhook"))),
                NotificationChannelResponse.class,
                created.id());
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().name()).isEqualTo("Ops webhook updated");
        assertThat(updateResponse.getBody().config()).containsEntry("urlRef", "ops-secondary-webhook");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/alerts/notification-channels/{id}",
                HttpMethod.DELETE,
                entity(ACCOUNT_ID, null),
                Void.class,
                created.id());
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void configRejectsInlineSecrets() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/alerts/notification-channels",
                HttpMethod.POST,
                entity(ACCOUNT_ID, Map.of(
                        "name", "Unsafe webhook",
                        "channelType", "WEBHOOK",
                        "config", Map.of("headers", Map.of("AuthorizationToken", "secret-value")))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void userIdScopesChannelsWhenAccountHeaderIsMissing() {
        ResponseEntity<NotificationChannelResponse> response = restTemplate.exchange(
                "/alerts/notification-channels",
                HttpMethod.POST,
                entity(null, Map.of(
                        "name", "User scoped email",
                        "channelType", "EMAIL",
                        "config", Map.of("toRef", "primary-contact"))),
                NotificationChannelResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().accountId()).isEqualTo(USER_ID);
    }

    @Test
    void notificationAttemptsTrackDeliveryStatusAndAreVisibleForAlert() {
        var alert = alertService.handleAnomaly(new AnomalyDetectedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "POWER_SPIKE",
                "MEDIUM",
                30,
                "Notification attempt status test.",
                Instant.parse("2026-05-13T16:15:00Z")));
        ResponseEntity<NotificationChannelResponse> createChannelResponse = restTemplate.exchange(
                "/alerts/notification-channels",
                HttpMethod.POST,
                entity(ACCOUNT_ID, Map.of(
                        "name", "Attempt webhook",
                        "channelType", "WEBHOOK",
                        "config", Map.of("urlRef", "attempt-webhook"),
                        "credentialsRef", "vault://alerts/webhooks/attempt")),
                NotificationChannelResponse.class);
        var channel = notificationChannelRepository.findById(createChannelResponse.getBody().id()).orElseThrow();

        var pending = notificationAttemptService.createPendingAttempt(alert, channel);
        var retrying = notificationAttemptService.markRetrying(pending.getId(), "HTTP_503", "Provider unavailable");
        var sent = notificationAttemptService.markSent(retrying.getId(), "Accepted by provider");

        ResponseEntity<List<NotificationAttemptResponse>> response = restTemplate.exchange(
                "/alerts/{id}/notification-attempts",
                HttpMethod.GET,
                entity(ACCOUNT_ID, null),
                new ParameterizedTypeReference<>() {
                },
                alert.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        NotificationAttemptResponse attempt = response.getBody().getFirst();
        assertThat(attempt.id()).isEqualTo(sent.getId());
        assertThat(attempt.alertId()).isEqualTo(alert.getId());
        assertThat(attempt.channelId()).isEqualTo(channel.getId());
        assertThat(attempt.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(attempt.channelType()).isEqualTo("WEBHOOK");
        assertThat(attempt.status()).isEqualTo("SENT");
        assertThat(attempt.retryCount()).isEqualTo(1);
        assertThat(attempt.lastErrorCode()).isNull();
        assertThat(attempt.providerResponseSummary()).isEqualTo("Accepted by provider");
        assertThat(attempt.sentAt()).isNotNull();
    }

    @Test
    void webhookNotificationDeliveryCreatesSentAttempt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>("");
        webhookServer = HttpServer.create(new InetSocketAddress(0), 0);
        webhookServer.createContext("/alerts", exchange -> {
            requests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        webhookServer.start();
        String webhookUrl = "http://localhost:" + webhookServer.getAddress().getPort() + "/alerts";

        ResponseEntity<NotificationChannelResponse> createChannelResponse = restTemplate.exchange(
                "/alerts/notification-channels",
                HttpMethod.POST,
                entity(ACCOUNT_ID, Map.of(
                        "name", "Delivery webhook",
                        "channelType", "WEBHOOK",
                        "config", Map.of("url", webhookUrl),
                        "enabled", true)),
                NotificationChannelResponse.class);
        Alert alert = saveAlertWithAccount();

        notificationDeliveryService.dispatchAlertCreated(alert);

        List<NotificationAttemptResponse> attempts = waitForAttempts(alert.getId(), "SENT");
        assertThat(requests.get()).isEqualTo(1);
        assertThat(requestBody.get()).contains("\"eventType\":\"ALERT_CREATED\"");
        assertThat(requestBody.get()).contains("\"alertId\":\"" + alert.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().channelId()).isEqualTo(createChannelResponse.getBody().id());
        assertThat(attempts.getFirst().status()).isEqualTo("SENT");
        assertThat(attempts.getFirst().providerResponseSummary()).isEqualTo("HTTP 202");
    }

    private Alert saveAlertWithAccount() {
        Alert alert = new Alert();
        alert.setId(UUID.randomUUID());
        alert.setDeviceId(UUID.randomUUID());
        alert.setAccountId(ACCOUNT_ID);
        alert.setSourceEventId(UUID.randomUUID());
        alert.setAnomalyId(UUID.randomUUID());
        alert.setDeduplicationKey("delivery-test:" + alert.getId());
        alert.setAlertType("POWER_SPIKE");
        alert.setTitle("Power spike detected");
        alert.setDescription("Webhook delivery test.");
        alert.setSeverity(AlertSeverity.WARNING);
        alert.setStatus(AlertStatus.OPEN);
        alert.setCreatedAt(Instant.parse("2026-05-13T17:15:00Z"));
        return alertRepository.saveAndFlush(alert);
    }

    private List<NotificationAttemptResponse> waitForAttempts(UUID alertId, String status) throws InterruptedException {
        for (int index = 0; index < 40; index++) {
            ResponseEntity<List<NotificationAttemptResponse>> response = restTemplate.exchange(
                    "/alerts/{id}/notification-attempts",
                    HttpMethod.GET,
                    entity(ACCOUNT_ID, null),
                    new ParameterizedTypeReference<>() {
                    },
                    alertId);
            if (response.getBody() != null && response.getBody().stream().anyMatch(attempt -> status.equals(attempt.status()))) {
                return response.getBody();
            }
            Thread.sleep(100);
        }
        return List.of();
    }

    private HttpEntity<Object> entity(UUID accountId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", USER_ID.toString());
        if (accountId != null) {
            headers.set("X-Account-Id", accountId.toString());
        }
        return new HttpEntity<>(body, headers);
    }
}
