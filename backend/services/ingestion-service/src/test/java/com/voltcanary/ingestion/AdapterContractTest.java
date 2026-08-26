package com.voltcanary.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.ingestion.adapter.api.AdapterCapabilities;
import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterManifest;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.adapter.http.HttpPollingResponseMapper;
import com.voltcanary.ingestion.adapter.http.HttpPollingTelemetryAdapter;
import com.voltcanary.ingestion.adapter.mqtt.MqttPayloadMapper;
import com.voltcanary.ingestion.adapter.mqtt.MqttTelemetryAdapter;
import com.voltcanary.ingestion.adapter.mqtt.MqttTopicResolver;
import com.voltcanary.ingestion.adapter.opcua.OpcUaTelemetryAdapter;
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

    @Test
    void mqttAdapterRegistersInboundMqttManifest() {
        MqttTelemetryAdapter mqttAdapter = new MqttTelemetryAdapter(
                new MqttTopicResolver(),
                new MqttPayloadMapper(new ObjectMapper()));

        assertThat(mqttAdapter.adapterId()).isEqualTo("generic-mqtt");
        assertThat(mqttAdapter.adapterType()).isEqualTo(AdapterType.GENERIC_MQTT);
        assertThat(mqttAdapter.manifest().transport()).isEqualTo(TransportType.MQTT);
        assertThat(mqttAdapter.manifest().protocol()).isEqualTo(ProtocolType.MQTT);
        assertThat(mqttAdapter.manifest().capabilities().inbound()).isTrue();
    }

    @Test
    void httpPollingAdapterRegistersPollingManifest() {
        HttpPollingTelemetryAdapter adapter = new HttpPollingTelemetryAdapter(
                ignored -> java.util.Optional.empty(),
                new HttpPollingResponseMapper(new ObjectMapper()));

        assertThat(adapter.adapterId()).isEqualTo("generic-http-polling");
        assertThat(adapter.adapterType()).isEqualTo(AdapterType.GENERIC_HTTP_POLLING);
        assertThat(adapter.manifest().transport()).isEqualTo(TransportType.HTTP);
        assertThat(adapter.manifest().protocol()).isEqualTo(ProtocolType.HTTP);
        assertThat(adapter.manifest().capabilities().polling()).isTrue();
    }

    @Test
    void opcUaAdapterRegistersSubscriptionManifest() {
        OpcUaTelemetryAdapter adapter = new OpcUaTelemetryAdapter((ignored, handler) -> () -> {
        });

        assertThat(adapter.adapterId()).isEqualTo("generic-opc-ua");
        assertThat(adapter.adapterType()).isEqualTo(AdapterType.GENERIC_OPC_UA);
        assertThat(adapter.manifest().transport()).isEqualTo(TransportType.TCP);
        assertThat(adapter.manifest().protocol()).isEqualTo(ProtocolType.OPC_UA);
        assertThat(adapter.manifest().capabilities().subscription()).isTrue();
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
