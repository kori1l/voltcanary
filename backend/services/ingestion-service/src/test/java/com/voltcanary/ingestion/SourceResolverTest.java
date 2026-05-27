package com.voltcanary.ingestion;

import com.voltcanary.ingestion.device.DeviceValidationClient;
import com.voltcanary.ingestion.pipeline.IncomingTelemetryMessage;
import com.voltcanary.ingestion.pipeline.IngestionProtocol;
import com.voltcanary.ingestion.source.DeviceServiceSourceClient;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceResolverTest {

    private final DeviceServiceSourceClient sourceClient = mock(DeviceServiceSourceClient.class);
    private final SourceResolver resolver = new SourceResolver(
            new SourceRegistryCache(),
            new SourceTokenValidator(),
            sourceClient,
            mock(DeviceValidationClient.class));

    @Test
    void resolvesValidSourceToken() {
        String token = "vc_src_live_test_token";
        UUID deviceId = UUID.randomUUID();
        when(sourceClient.findByTokenPrefix("vc_src_live_test"))
                .thenReturn(source(token, deviceId, "ACTIVE", "REST"));

        assertThat(resolver.resolve(message(token)).deviceId()).isEqualTo(deviceId);
    }

    @Test
    void rejectsInvalidToken() {
        when(sourceClient.findByTokenPrefix("vc_src_live_test"))
                .thenReturn(source("different-token", UUID.randomUUID(), "ACTIVE", "REST"));

        assertThatThrownBy(() -> resolver.resolve(message("vc_src_live_test_token")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void rejectsInactiveSource() {
        String token = "vc_src_live_test_token";
        when(sourceClient.findByTokenPrefix("vc_src_live_test"))
                .thenReturn(source(token, UUID.randomUUID(), "REVOKED", "REST"));

        assertThatThrownBy(() -> resolver.resolve(message(token)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void rejectsTokenBoundToDifferentSubmittedDevice() {
        String token = "vc_src_live_test_token";
        UUID registeredDeviceId = UUID.randomUUID();
        when(sourceClient.findByTokenPrefix("vc_src_live_test"))
                .thenReturn(source(token, registeredDeviceId, "ACTIVE", "REST"));

        IncomingTelemetryMessage message = new IncomingTelemetryMessage(
                "sim-fridge-001",
                Instant.now(),
                Map.of("activePowerW", 120),
                IngestionProtocol.HTTP,
                null,
                null,
                token,
                UUID.randomUUID(),
                null,
                false);

        assertThatThrownBy(() -> resolver.resolve(message))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void rejectsTokenBoundToDifferentSubmittedSourceId() {
        String token = "vc_src_live_test_token";
        when(sourceClient.findByTokenPrefix("vc_src_live_test"))
                .thenReturn(source(token, UUID.randomUUID(), "ACTIVE", "REST"));

        IncomingTelemetryMessage message = new IncomingTelemetryMessage(
                "different-source",
                Instant.now(),
                Map.of("activePowerW", 120),
                IngestionProtocol.HTTP,
                null,
                null,
                token,
                null,
                null,
                false);

        assertThatThrownBy(() -> resolver.resolve(message))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    private IncomingTelemetryMessage message(String token) {
        return new IncomingTelemetryMessage(
                "sim-fridge-001",
                Instant.now(),
                Map.of("activePowerW", 120),
                IngestionProtocol.HTTP,
                null,
                null,
                token,
                null,
                null,
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
                sha256Hex(token));
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
