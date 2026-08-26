package com.voltcanary.ingestion;

import com.voltcanary.ingestion.adapter.api.AdapterCapabilities;
import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterManifest;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.adapter.registry.DefaultIngestionAdapterRegistry;
import com.voltcanary.ingestion.adapter.registry.DefaultIngestionAdapterResolver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdapterContractTest {

    @Test
    void manifestRequiresStableIdentityAndCapabilities() {
        assertThatThrownBy(() -> new AdapterManifest(
                " ",
                AdapterType.GENERIC_HTTP_PUSH,
                "HTTP push",
                TransportType.HTTP,
                ProtocolType.REST,
                AdapterCapabilities.of(AdapterCapability.INBOUND),
                "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapterId");

        assertThatThrownBy(() -> new AdapterManifest(
                "generic-http-push",
                AdapterType.GENERIC_HTTP_PUSH,
                "HTTP push",
                TransportType.HTTP,
                ProtocolType.REST,
                new AdapterCapabilities(Set.of()),
                "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capabilities");
    }

    @Test
    void registryResolvesAdaptersBySourceRuntimeConfig() {
        TelemetryAdapter httpAdapter = adapter(
                "generic-http-push",
                AdapterType.GENERIC_HTTP_PUSH,
                TransportType.HTTP,
                ProtocolType.REST);
        DefaultIngestionAdapterRegistry registry = new DefaultIngestionAdapterRegistry(List.of(httpAdapter));
        DefaultIngestionAdapterResolver resolver = new DefaultIngestionAdapterResolver(registry);

        SourceRuntimeConfig config = new SourceRuntimeConfig(
                "source-1",
                null,
                null,
                null,
                AdapterType.GENERIC_HTTP_PUSH,
                ProtocolType.REST,
                TransportType.HTTP,
                "ACTIVE",
                "mapping.v1",
                "adapter.v1",
                Map.of(),
                Map.of(),
                Map.of(),
                "eu",
                7);

        assertThat(registry.find(AdapterType.GENERIC_HTTP_PUSH)).containsSame(httpAdapter);
        assertThat(registry.findByCapability(AdapterCapability.INBOUND, TelemetryAdapter.class))
                .containsExactly(httpAdapter);
        assertThat(resolver.resolve(config)).containsSame(httpAdapter);
    }

    @Test
    void registryRejectsDuplicateAdapterTypes() {
        TelemetryAdapter first = adapter(
                "generic-http-push",
                AdapterType.GENERIC_HTTP_PUSH,
                TransportType.HTTP,
                ProtocolType.REST);
        TelemetryAdapter second = adapter(
                "generic-http-push-copy",
                AdapterType.GENERIC_HTTP_PUSH,
                TransportType.HTTP,
                ProtocolType.REST);

        assertThatThrownBy(() -> new DefaultIngestionAdapterRegistry(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate ingestion adapter type");
    }

    private TelemetryAdapter adapter(
            String adapterId,
            AdapterType adapterType,
            TransportType transport,
            ProtocolType protocol) {
        return () -> new AdapterManifest(
                adapterId,
                adapterType,
                adapterId,
                transport,
                protocol,
                AdapterCapabilities.of(AdapterCapability.INBOUND),
                "v1");
    }
}
