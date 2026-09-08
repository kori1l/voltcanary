package com.voltcanary.alert.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.alert.notification.config.AlertNotificationProperties;
import com.voltcanary.alert.notification.port.AlertNotificationRequest;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

class NotificationProviderAdapterTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void emailAdapterValidatesAndSendsCanonicalMessage() throws Exception {
        JavaMailSender mailSender = mailSender();
        EmailNotificationProviderAdapter adapter = new EmailNotificationProviderAdapter(
                mailSenderProvider(mailSender),
                emailProperties());
        NotificationDeliveryCommand command = command(
                "EMAIL",
                Map.of(
                        "recipients", List.of("ops@example.com"),
                        "cc", List.of("lead@example.com"),
                        "fromAlias", "VoltCanary",
                        "subjectPrefix", "[VoltCanary]"));

        adapter.validateConfig(command.channelConfig());
        NotificationDeliveryResult result = adapter.deliver(command);

        assertThat(result.status()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(result.providerResponseSummary()).isEqualTo("EMAIL accepted");
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void emailAdapterMapsProviderFailures() throws Exception {
        JavaMailSender retrySender = mailSender();
        doThrow(new MailSendException("smtp offline")).when(retrySender).send(any(MimeMessage.class));
        JavaMailSender permanentSender = mailSender();
        doThrow(new MailAuthenticationException("bad credentials")).when(permanentSender).send(any(MimeMessage.class));

        NotificationDeliveryResult retryable = new EmailNotificationProviderAdapter(
                mailSenderProvider(retrySender),
                emailProperties())
                .deliver(command("EMAIL", Map.of("recipients", List.of("ops@example.com"))));
        NotificationDeliveryResult permanent = new EmailNotificationProviderAdapter(
                mailSenderProvider(permanentSender),
                emailProperties())
                .deliver(command("EMAIL", Map.of("recipients", List.of("ops@example.com"))));

        assertThat(retryable.status()).isEqualTo(NotificationDeliveryStatus.RETRYABLE_FAILURE);
        assertThat(retryable.errorCode()).isEqualTo("MailSendException");
        assertThat(permanent.status()).isEqualTo(NotificationDeliveryStatus.PERMANENT_FAILURE);
        assertThat(permanent.errorCode()).isEqualTo("MailAuthenticationException");
    }

    @Test
    void telegramAdapterMapsProviderResponsesAndSendsNonSecretPayload() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server = telegramServer(200, "{\"ok\":true,\"result\":{\"message_id\":42}}", requestBody);
        TelegramNotificationProviderAdapter adapter = new TelegramNotificationProviderAdapter(
                telegramProperties(server),
                RestClient.builder(),
                new ObjectMapper());
        NotificationDeliveryCommand command = command(
                "TELEGRAM",
                Map.of(
                        "chatId", "-1001234567890",
                        "parseMode", "MarkdownV2",
                        "disableWebPagePreview", true));

        adapter.validateConfig(command.channelConfig());
        NotificationDeliveryResult result = adapter.deliver(command);

        assertThat(result.status()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(result.providerMessageId()).isEqualTo("42");
        assertThat(requestBody.get()).contains("\"chat_id\":\"-1001234567890\"");
        assertThat(requestBody.get()).contains("\"disable_web_page_preview\":true");
        assertThat(requestBody.get()).doesNotContain("fake-token");
    }

    @Test
    void telegramAdapterMapsRetryableAndPermanentFailures() throws Exception {
        AtomicReference<String> ignored = new AtomicReference<>("");
        server = telegramServer(429, "{\"ok\":false,\"description\":\"retry later\"}", ignored);
        TelegramNotificationProviderAdapter retryAdapter = new TelegramNotificationProviderAdapter(
                telegramProperties(server),
                RestClient.builder(),
                new ObjectMapper());

        NotificationDeliveryResult retryable = retryAdapter.deliver(
                command("TELEGRAM", Map.of("chatId", "-1001234567890")));
        server.stop(0);
        server = telegramServer(400, "{\"ok\":false,\"description\":\"chat not found\"}", ignored);
        TelegramNotificationProviderAdapter permanentAdapter = new TelegramNotificationProviderAdapter(
                telegramProperties(server),
                RestClient.builder(),
                new ObjectMapper());
        NotificationDeliveryResult permanent = permanentAdapter.deliver(
                command("TELEGRAM", Map.of("chatId", "-1001234567890")));

        assertThat(retryable.status()).isEqualTo(NotificationDeliveryStatus.RETRYABLE_FAILURE);
        assertThat(retryable.errorCode()).isEqualTo("HTTP_429");
        assertThat(permanent.status()).isEqualTo(NotificationDeliveryStatus.PERMANENT_FAILURE);
        assertThat(permanent.errorCode()).isEqualTo("HTTP_400");
    }

    private JavaMailSender mailSender() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(
                new MimeMessage(Session.getInstance(new Properties())));
        return mailSender;
    }

    private ObjectProvider<JavaMailSender> mailSenderProvider(JavaMailSender mailSender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getIfAvailable() {
                return mailSender;
            }
        };
    }

    private AlertNotificationProperties emailProperties() {
        AlertNotificationProperties properties = new AlertNotificationProperties();
        properties.getEmail().setFromAddress("alerts@example.com");
        return properties;
    }

    private AlertNotificationProperties telegramProperties(HttpServer server) {
        AlertNotificationProperties properties = new AlertNotificationProperties();
        properties.getTelegram().setApiBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.getTelegram().setBotToken("fake-token");
        return properties;
    }

    private HttpServer telegramServer(int status, String response, AtomicReference<String> requestBody)
            throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/botfake-token/sendMessage", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(responseBytes);
            }
        });
        httpServer.start();
        return httpServer;
    }

    private NotificationDeliveryCommand command(String channelType, Map<String, Object> config) {
        return new NotificationDeliveryCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                channelType,
                "event:channel",
                "ALERT_CREATED",
                config,
                null,
                new AlertNotificationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "power-spike-v2",
                        Instant.parse("2026-09-08T10:00:00Z"),
                        Instant.parse("2026-09-08T10:15:00Z"),
                        "input-hash",
                        "ALERT_CREATED",
                        "POWER_SPIKE",
                        "HIGH",
                        "OPEN",
                        "Power spike detected",
                        "Power moved outside the expected range.",
                        Instant.parse("2026-09-08T10:15:00Z"),
                        "event:channel"));
    }
}
