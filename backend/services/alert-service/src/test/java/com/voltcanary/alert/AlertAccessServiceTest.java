package com.voltcanary.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.voltcanary.alert.config.AlertAuthorizationProperties;
import com.voltcanary.alert.config.AlertInternalProperties;
import com.voltcanary.alert.service.AlertAccessService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class AlertAccessServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void checksDeviceOwnershipThroughDeviceService() throws IOException {
        UUID deviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/devices/" + deviceId + "/access", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("userId=" + userId);
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token")).isEqualTo("internal-token");
            byte[] response = "{\"allowed\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        AlertAccessService accessService = accessService("http://localhost:" + server.getAddress().getPort());

        assertThat(accessService.canReadDevice(deviceId, userId.toString(), "USER", "corr-1")).isTrue();
    }

    @Test
    void resolvesDeviceAccountThroughDeviceService() throws IOException {
        UUID deviceId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/devices/" + deviceId + "/owner", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token")).isEqualTo("internal-token");
            byte[] response = ("{\"deviceId\":\"" + deviceId + "\",\"accountId\":\"" + accountId + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();

        AlertAccessService accessService = accessService("http://localhost:" + server.getAddress().getPort());

        assertThat(accessService.resolveDeviceAccountId(deviceId, "corr-1")).isEqualTo(accountId);
    }

    @Test
    void requiresAuthenticatedUserForProtectedAccess() {
        AlertAccessService accessService = accessService("http://localhost:1");

        assertThatThrownBy(() -> accessService.requireReadAccess(UUID.randomUUID(), null, "USER", "corr-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    private AlertAccessService accessService(String baseUrl) {
        AlertAuthorizationProperties authorizationProperties = new AlertAuthorizationProperties();
        authorizationProperties.setEnabled(true);
        authorizationProperties.setDeviceServiceBaseUrl(baseUrl);
        AlertInternalProperties internalProperties = new AlertInternalProperties();
        internalProperties.setServiceToken("internal-token");
        return new AlertAccessService(authorizationProperties, RestClient.builder(), internalProperties);
    }
}
