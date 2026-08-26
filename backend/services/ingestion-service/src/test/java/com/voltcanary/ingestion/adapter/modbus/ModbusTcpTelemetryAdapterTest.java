package com.voltcanary.ingestion.adapter.modbus;

import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModbusTcpTelemetryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void manifestRegistersGenericModbusTcpPollingCapability() {
        ModbusTcpTelemetryAdapter adapter = adapter(config -> List.of());

        assertThat(adapter.adapterId()).isEqualTo("generic-modbus-tcp");
        assertThat(adapter.adapterType()).isEqualTo(AdapterType.GENERIC_MODBUS_TCP);
        assertThat(adapter.manifest().protocol()).isEqualTo(ProtocolType.MODBUS_TCP);
        assertThat(adapter.manifest().transport()).isEqualTo(TransportType.TCP);
        assertThat(adapter.manifest().capabilities().polling()).isTrue();
    }

    @Test
    void pollsRegistersAndMapsCanonicalTelemetryMetrics() {
        SourceRuntimeConfig config = config(
                Map.of(
                        "host", "plc-1.internal",
                        "port", 1502,
                        "unitId", 7,
                        "pollingInterval", "1s",
                        "timeout", "2s",
                        "timestampPolicy", "POLL_TIME"),
                Map.of("registers", List.of(
                        Map.of(
                                "metric", "voltageV",
                                "function", "HOLDING_REGISTER",
                                "address", 0,
                                "dataType", "UINT16",
                                "scale", 0.1,
                                "unit", "V"),
                        Map.of(
                                "metric", "activePowerW",
                                "function", "INPUT_REGISTER",
                                "address", 10,
                                "dataType", "INT32",
                                "scale", 1,
                                "wordOrder", "BIG_ENDIAN"))));
        RecordingClient client = new RecordingClient(List.of(
                new ModbusReadResult(new ModbusReadRequest(ModbusRegisterFunction.HOLDING_REGISTER, 0, 1), new int[] {2301}),
                new ModbusReadResult(new ModbusReadRequest(ModbusRegisterFunction.INPUT_REGISTER, 10, 2), new int[] {0, 840})));

        List<IncomingTelemetryMessage> messages = adapter(client).poll(config);

        assertThat(client.host).isEqualTo("plc-1.internal");
        assertThat(client.port).isEqualTo(1502);
        assertThat(client.unitId).isEqualTo(7);
        assertThat(messages).hasSize(1);
        IncomingTelemetryMessage message = messages.get(0);
        assertThat(message.sourceId()).isEqualTo(config.sourceId());
        assertThat(message.deviceId()).isEqualTo(config.deviceId());
        assertThat(message.accountId()).isEqualTo(config.accountId());
        assertThat(message.occurredAt()).isEqualTo(NOW);
        assertThat(message.receivedAt()).isEqualTo(NOW);
        assertThat(message.metrics()).containsEntry("activePowerW", 840L);
        assertThat((Double) message.metrics().get("voltageV")).isCloseTo(230.1, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(message.origin().adapterType()).isEqualTo(AdapterType.GENERIC_MODBUS_TCP);
        assertThat(message.origin().metadata()).containsEntry("endpointHost", "plc-1.internal");
        assertThat(message.context().metadata()).containsEntry("unit.voltageV", "V");
    }

    @Test
    void rejectsInvalidMappingBeforeReadingEndpoint() {
        SourceRuntimeConfig config = config(
                Map.of("host", "plc-1.internal", "pollingInterval", "1s"),
                Map.of("registers", List.of(Map.of(
                        "metric", "voltageV",
                        "address", -1,
                        "dataType", "UINT16"))));
        RecordingClient client = new RecordingClient(List.of());

        assertThatThrownBy(() -> adapter(client).poll(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address");
        assertThat(client.reads).isZero();
    }

    @Test
    void propagatesOperationFailuresForConnectorHealthAndBackoff() {
        SourceRuntimeConfig config = config(
                Map.of("host", "plc-1.internal", "pollingInterval", "1s"),
                Map.of("registers", List.of(Map.of(
                        "metric", "voltageV",
                        "address", 0,
                        "dataType", "UINT16"))));
        ModbusTcpTelemetryAdapter adapter = adapter(ignored -> {
            throw new IllegalStateException("Modbus TCP operation timed out");
        });

        assertThatThrownBy(() -> adapter.poll(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void decodesLittleEndianFloat32() {
        SourceRuntimeConfig config = config(
                Map.of("host", "plc-1.internal", "pollingInterval", "1s"),
                Map.of("registers", List.of(Map.of(
                        "metric", "temperatureC",
                        "address", 4,
                        "dataType", "FLOAT32",
                        "byteOrder", "BIG_ENDIAN",
                        "wordOrder", "LITTLE_ENDIAN"))));
        RecordingClient client = new RecordingClient(List.of(new ModbusReadResult(
                new ModbusReadRequest(ModbusRegisterFunction.HOLDING_REGISTER, 4, 2),
                new int[] {0x0000, 0x41C8})));

        List<IncomingTelemetryMessage> messages = adapter(client).poll(config);

        assertThat(messages.get(0).metrics()).containsEntry("temperatureC", 25L);
    }

    private ModbusTcpTelemetryAdapter adapter(ModbusTcpClientOperations client) {
        return new ModbusTcpTelemetryAdapter(client, new ModbusRegisterValueMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SourceRuntimeConfig config(Map<String, Object> adapterConfig, Map<String, Object> mapping) {
        return new SourceRuntimeConfig(
                "source-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AdapterType.GENERIC_MODBUS_TCP,
                ProtocolType.MODBUS_TCP,
                TransportType.TCP,
                "ACTIVE",
                "v1",
                "v1",
                mapping,
                adapterConfig,
                Map.of("networkAccess", "tunnel://plant-a/plc-1"),
                "default",
                1L);
    }

    private static final class RecordingClient implements ModbusTcpClientOperations {
        private final List<ModbusReadResult> results;
        private int reads;
        private String host;
        private int port;
        private int unitId;

        private RecordingClient(List<ModbusReadResult> results) {
            this.results = results;
        }

        @Override
        public List<ModbusReadResult> read(ModbusTcpAdapterConfig config) {
            reads++;
            host = config.host();
            port = config.port();
            unitId = config.unitId();
            return results;
        }
    }
}
