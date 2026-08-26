package com.voltcanary.ingestion.adapter.opcua;

import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.SubscriptionTelemetrySink;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpcUaTelemetryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void startsSubscriptionAndMapsNodeChangesToCanonicalTelemetry() {
        RecordingClient client = new RecordingClient();
        RecordingSink sink = new RecordingSink();
        SourceRuntimeConfig config = config(
                Map.of(
                        "endpointUrl", "opc.tcp://plc-1.internal:4840",
                        "securityPolicy", "Basic256Sha256",
                        "authenticationMode", "USERNAME",
                        "publishingInterval", "1s",
                        "samplingInterval", "250ms",
                        "timestampPolicy", "SOURCE_TIME"),
                Map.of("nodes", List.of(Map.of(
                        "metric", "voltageV",
                        "nodeId", "ns=2;s=Line1.Voltage",
                        "dataType", "DOUBLE",
                        "scale", 0.1,
                        "unit", "V"))),
                Map.of("username", "env:OPCUA_USER", "password", "env:OPCUA_PASSWORD"));

        adapter(client).startSubscription(config, sink);
        client.handler.onValue(new OpcUaNodeValue(
                client.config.nodes().get(0),
                2301,
                Instant.parse("2026-01-01T00:00:05Z"),
                Instant.parse("2026-01-01T00:00:06Z")));

        assertThat(client.starts).isEqualTo(1);
        assertThat(client.config.endpoint().getHost()).isEqualTo("plc-1.internal");
        assertThat(client.config.usernameCredentialRef()).isEqualTo("env:OPCUA_USER");
        assertThat(sink.messages).hasSize(1);
        IncomingTelemetryMessage message = sink.messages.get(0);
        assertThat(message.sourceId()).isEqualTo(config.sourceId());
        assertThat(message.occurredAt()).isEqualTo(Instant.parse("2026-01-01T00:00:05Z"));
        assertThat(message.receivedAt()).isEqualTo(NOW);
        assertThat((Double) message.metrics().get("voltageV"))
                .isCloseTo(230.1, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(message.origin().adapterType()).isEqualTo(AdapterType.GENERIC_OPC_UA);
        assertThat(message.origin().metadata()).containsEntry("endpointHost", "plc-1.internal");
        assertThat(message.origin().metadata()).containsEntry("securityPolicy", "Basic256Sha256");
        assertThat(message.context().metadata()).containsEntry("unit.voltageV", "V");
    }

    @Test
    void rejectsInvalidNodeMappingBeforeOpeningSession() {
        RecordingClient client = new RecordingClient();
        SourceRuntimeConfig config = config(
                Map.of("endpointUrl", "opc.tcp://plc-1.internal:4840"),
                Map.of("nodes", List.of(Map.of("metric", "voltageV"))),
                Map.of());

        assertThatThrownBy(() -> adapter(client).startSubscription(config, new RecordingSink()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeId or browsePath");
        assertThat(client.starts).isZero();
    }

    @Test
    void forwardsSessionFailuresForConnectorHealth() {
        RecordingClient client = new RecordingClient();
        RecordingSink sink = new RecordingSink();
        SourceRuntimeConfig config = config(
                Map.of("endpointUrl", "opc.tcp://plc-1.internal:4840"),
                Map.of("nodes", List.of(Map.of("metric", "voltageV", "nodeId", "ns=2;s=Line1.Voltage"))),
                Map.of());

        adapter(client).startSubscription(config, sink);
        client.handler.onFailure(new IllegalStateException("OPC UA session lost"));

        assertThat(sink.failures).hasSize(1);
        assertThat(sink.failures.get(0)).hasMessageContaining("session lost");
    }

    @Test
    void stopClosesActiveSubscription() {
        RecordingClient client = new RecordingClient();
        SourceRuntimeConfig config = config(
                Map.of("endpointUrl", "opc.tcp://plc-1.internal:4840"),
                Map.of("nodes", List.of(Map.of("metric", "voltageV", "nodeId", "ns=2;s=Line1.Voltage"))),
                Map.of());

        OpcUaTelemetryAdapter adapter = adapter(client);
        adapter.startSubscription(config, new RecordingSink());
        adapter.stopSubscription(config);

        assertThat(client.handle.closed).isTrue();
    }

    private OpcUaTelemetryAdapter adapter(RecordingClient client) {
        return new OpcUaTelemetryAdapter(client, new OpcUaValueMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
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
                AdapterType.GENERIC_OPC_UA,
                ProtocolType.OPC_UA,
                TransportType.TCP,
                "ACTIVE",
                "v1",
                "v1",
                mapping,
                adapterConfig,
                credentialReferences,
                "default",
                1L);
    }

    private static final class RecordingClient implements OpcUaClientOperations {
        private int starts;
        private OpcUaAdapterConfig config;
        private OpcUaValueChangeHandler handler;
        private RecordingHandle handle;

        @Override
        public OpcUaSubscriptionHandle subscribe(OpcUaAdapterConfig config, OpcUaValueChangeHandler handler) {
            starts++;
            this.config = config;
            this.handler = handler;
            this.handle = new RecordingHandle();
            return handle;
        }
    }

    private static final class RecordingHandle implements OpcUaSubscriptionHandle {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingSink implements SubscriptionTelemetrySink {
        private final List<IncomingTelemetryMessage> messages = new ArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();

        @Override
        public void accept(IncomingTelemetryMessage message) {
            messages.add(message);
        }

        @Override
        public void reject(SourceRuntimeConfig sourceRuntimeConfig, RuntimeException exception) {
            failures.add(exception);
        }
    }
}
