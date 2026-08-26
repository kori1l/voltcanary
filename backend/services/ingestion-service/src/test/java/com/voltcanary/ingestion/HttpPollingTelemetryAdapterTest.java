package com.voltcanary.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.adapter.http.CredentialProvider;
import com.voltcanary.ingestion.adapter.http.HttpPollingResponseMapper;
import com.voltcanary.ingestion.adapter.http.HttpPollingTelemetryAdapter;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpPollingTelemetryAdapterTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void manifestRegistersGenericHttpPollingCapability() {
        HttpPollingTelemetryAdapter adapter = adapter(Map.of());

        assertThat(adapter.adapterId()).isEqualTo("generic-http-polling");
        assertThat(adapter.adapterType()).isEqualTo(AdapterType.GENERIC_HTTP_POLLING);
        assertThat(adapter.manifest().protocol()).isEqualTo(ProtocolType.HTTP);
        assertThat(adapter.manifest().transport()).isEqualTo(TransportType.HTTP);
        assertThat(adapter.manifest().capabilities().polling()).isTrue();
    }

    @Test
    void pollsEndpointAndMapsResponseIntoCanonicalTelemetry() throws IOException {
        server = server(exchange -> respond(exchange, 200, """
                {
                  "sampledAt": "2026-01-01T00:00:00Z",
                  "payload": {
                    "power": 840.2,
                    "voltage": 230.1
                  }
                }
                """));
        SourceRuntimeConfig config = config(
                Map.of(
                        "url", endpoint("/reading"),
                        "pollingInterval", "1s",
                        "timeout", "2s"),
                Map.of(
                        "timestampPath", "sampledAt",
                        "metrics", Map.of(
                                "activePowerW", "payload.power",
                                "voltageV", "payload.voltage")),
                Map.of());

        List<IncomingTelemetryMessage> messages = adapter(Map.of()).poll(config);

        assertThat(messages).hasSize(1);
        IncomingTelemetryMessage message = messages.get(0);
        assertThat(message.sourceId()).isEqualTo(config.sourceId());
        assertThat(message.deviceId()).isEqualTo(config.deviceId());
        assertThat(message.accountId()).isEqualTo(config.accountId());
        assertThat(message.occurredAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(message.metrics()).containsEntry("activePowerW", 840.2);
        assertThat(message.metrics()).containsEntry("voltageV", 230.1);
        assertThat(message.origin().adapterType()).isEqualTo(AdapterType.GENERIC_HTTP_POLLING);
        assertThat(message.origin().metadata()).containsEntry("endpointHost", "localhost");
        assertThat(message.context().metadata()).containsEntry("httpStatus", "200");
    }

    @Test
    void resolvesHeadersThroughCredentialReferences() throws IOException {
        server = server(exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer resolved-token".equals(authorization)) {
                respond(exchange, 401, "{}");
                return;
            }
            respond(exchange, 200, """
                    {
                      "timestamp": "2026-01-01T00:00:00Z",
                      "metrics": { "activePowerW": 42.0 }
                    }
                    """);
        });
        SourceRuntimeConfig config = config(
                Map.of(
                        "url", endpoint("/secure"),
                        "pollingInterval", "1s",
                        "credentialHeaders", Map.of("Authorization", "apiToken")),
                Map.of(),
                Map.of("apiToken", "vault://sources/source-1/api-token"));

        List<IncomingTelemetryMessage> messages = adapter(Map.of(
                "vault://sources/source-1/api-token", "Bearer resolved-token")).poll(config);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).metrics()).containsEntry("activePowerW", 42.0);
    }

    @Test
    void rejectsInvalidConfigBeforePolling() {
        SourceRuntimeConfig config = config(
                Map.of("url", "ftp://example.invalid/reading", "pollingInterval", "1s"),
                Map.of(),
                Map.of());

        assertThatThrownBy(() -> adapter(Map.of()).poll(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");
    }

    private HttpPollingTelemetryAdapter adapter(Map<String, String> credentials) {
        CredentialProvider credentialProvider = reference -> Optional.ofNullable(credentials.get(reference));
        return new HttpPollingTelemetryAdapter(credentialProvider, new HttpPollingResponseMapper(new ObjectMapper()));
    }

    private SourceRuntimeConfig config(
            Map<String, Object> adapterConfig,
            Map<String, Object> mapping,
            Map<String, String> credentialReferences) {
        return new SourceRuntimeConfig(
                "source-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AdapterType.GENERIC_HTTP_POLLING,
                ProtocolType.HTTP,
                TransportType.HTTP,
                "ACTIVE",
                "v1",
                "v1",
                mapping,
                adapterConfig,
                credentialReferences,
                "default",
                1L);
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", handler::handle);
        httpServer.start();
        return httpServer;
    }

    private String endpoint(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
