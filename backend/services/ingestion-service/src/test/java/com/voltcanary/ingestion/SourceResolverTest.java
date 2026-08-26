package com.voltcanary.ingestion;

import com.voltcanary.ingestion.canonical.IngestionContext;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import com.voltcanary.ingestion.source.InternalDataSourceResponse;
import com.voltcanary.ingestion.source.SourceRegistryCache;
import com.voltcanary.ingestion.source.SourceResolver;
import com.voltcanary.ingestion.source.SourceTokenValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceResolverTest {

    private final SourceRegistryCache cache = new SourceRegistryCache();
    private final SourceResolver resolver = new SourceResolver(
            cache,
            new SourceTokenValidator());

    @Test
    void resolvesValidSourceToken() {
        String token = "vc_src_live_test_token";
        UUID deviceId = UUID.randomUUID();
        cache.put(source(token, deviceId, "ACTIVE", "REST"), 1L);

        assertThat(resolver.resolve(message(token)).deviceId()).isEqualTo(deviceId);
    }

    @Test
    void rejectsInvalidToken() {
        cache.put(source("different-token", UUID.randomUUID(), "ACTIVE", "REST"), 1L);

        assertThatThrownBy(() -> resolver.resolve(message("vc_src_live_test_token")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void rejectsUnknownTokenPrefixFromLocalReadModel() {
        assertThatThrownBy(() -> resolver.resolve(message("vc_src_live_test_token")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("Invalid source token");
    }

    @Test
    void rejectsInactiveSource() {
        String token = "vc_src_live_test_token";
        cache.put(source(token, UUID.randomUUID(), "REVOKED", "REST"), 1L);

        assertThatThrownBy(() -> resolver.resolve(message(token)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void rejectsTokenBoundToDifferentSubmittedDevice() {
        String token = "vc_src_live_test_token";
        UUID registeredDeviceId = UUID.randomUUID();
        cache.put(source(token, registeredDeviceId, "ACTIVE", "REST"), 1L);

        IncomingTelemetryMessage message = new IncomingTelemetryMessage(
                "sim-fridge-001",
                UUID.randomUUID(),
                null,
                Instant.now(),
                Instant.now(),
                Map.of("activePowerW", 120),
                TelemetryOrigin.httpPush(),
                new IngestionContext(null, token, null, Map.of()),
                false);

        assertThatThrownBy(() -> resolver.resolve(message))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void rejectsTokenBoundToDifferentSubmittedSourceId() {
        String token = "vc_src_live_test_token";
        cache.put(source(token, UUID.randomUUID(), "ACTIVE", "REST"), 1L);

        IncomingTelemetryMessage message = new IncomingTelemetryMessage(
                "different-source",
                null,
                null,
                Instant.now(),
                Instant.now(),
                Map.of("activePowerW", 120),
                TelemetryOrigin.httpPush(),
                new IngestionContext(null, token, null, Map.of()),
                false);

        assertThatThrownBy(() -> resolver.resolve(message))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void resolvesMqttFromLocalReadModel() {
        UUID deviceId = UUID.randomUUID();
        cache.put(source(null, deviceId, "ACTIVE", "MQTT"), 1L);

        IncomingTelemetryMessage message = new IncomingTelemetryMessage(
                "sim-fridge-001",
                null,
                null,
                Instant.now(),
                Instant.now(),
                Map.of("activePowerW", 120),
                TelemetryOrigin.mqtt("voltcanary/sources/sim-fridge-001/telemetry"),
                new IngestionContext(null, null, null, Map.of()),
                false);

        assertThat(resolver.resolve(message).deviceId()).isEqualTo(deviceId);
    }

    private IncomingTelemetryMessage message(String token) {
        return new IncomingTelemetryMessage(
                "sim-fridge-001",
                null,
                null,
                Instant.now(),
                Instant.now(),
                Map.of("activePowerW", 120),
                TelemetryOrigin.httpPush(),
                new IngestionContext(null, token, null, Map.of()),
                false);
    }

    private InternalDataSourceResponse source(String token, UUID deviceId, String status, String protocol) {
        return new InternalDataSourceResponse(
                UUID.randomUUID(),
                deviceId,
                UUID.randomUUID(),
                "sim-fridge-001",
                "SIMULATOR",
                protocol,
                status,
                token == null ? null : token.substring(0, Math.min(16, token.length())),
                token == null ? null : sha256Hex(token));
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
