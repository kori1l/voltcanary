package com.voltcanary.ingestion;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.source.SourceRegistryCache;
import com.voltcanary.ingestion.source.SourceRuntimeConfigStore;
import com.voltcanary.ingestion.source.SourceRuntimeConfigConsumer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRuntimeConfigConsumerTest {

    private final SourceRegistryCache cache = new SourceRegistryCache();
    private final SourceRuntimeConfigStore runtimeConfigStore = new SourceRuntimeConfigStore();
    private final SourceRuntimeConfigConsumer consumer = new SourceRuntimeConfigConsumer(cache, runtimeConfigStore);

    @Test
    void appliesRuntimeConfigToLocalReadModel() {
        UUID dataSourceId = UUID.randomUUID();
        SourceRuntimeConfigEvent event = event(dataSourceId, "ACTIVE", 10L);

        consumer.consume(event, dataSourceId.toString());

        assertThat(cache.getByTokenPrefix("vc_src_live_test")).hasValueSatisfying(source -> {
            assertThat(source.dataSourceId()).isEqualTo(dataSourceId);
            assertThat(source.externalId()).isEqualTo("sim-fridge-001");
            assertThat(source.status()).isEqualTo("ACTIVE");
        });
        assertThat(cache.getByExternalId("sim-fridge-001")).isPresent();
        assertThat(runtimeConfigStore.get(dataSourceId)).hasValueSatisfying(config -> {
            assertThat(config.adapterType()).isEqualTo(AdapterType.GENERIC_HTTP_PUSH);
            assertThat(config.version()).isEqualTo(10L);
        });
    }

    @Test
    void ignoresOlderRuntimeConfigVersions() {
        UUID dataSourceId = UUID.randomUUID();
        consumer.consume(event(dataSourceId, "DISABLED", 11L), dataSourceId.toString());
        consumer.consume(event(dataSourceId, "ACTIVE", 10L), dataSourceId.toString());

        assertThat(cache.getByExternalId("sim-fridge-001")).hasValueSatisfying(source ->
                assertThat(source.status()).isEqualTo("DISABLED"));
        assertThat(runtimeConfigStore.get(dataSourceId)).hasValueSatisfying(config ->
                assertThat(config.status()).isEqualTo("DISABLED"));
    }

    @Test
    void appliesModbusAndOpcUaRuntimeConfigWithoutRawSecrets() {
        UUID modbusId = UUID.randomUUID();
        UUID opcUaId = UUID.randomUUID();
        consumer.consume(event(
                modbusId,
                "modbus-line-1",
                "GENERIC_MODBUS_TCP",
                "MODBUS_TCP",
                "TCP",
                Map.of("registers", List.of(Map.of("metric", "voltageV", "address", 0))),
                Map.of("host", "plc-1.internal", "port", 502),
                Map.of("basicAuth", "vault://sources/modbus-line-1/basic"),
                "eu-west-1",
                "ACTIVE",
                12L), modbusId.toString());
        consumer.consume(event(
                opcUaId,
                "opcua-line-1",
                "GENERIC_OPC_UA",
                "OPC_UA",
                "TCP",
                Map.of("nodes", List.of(Map.of("metric", "temperatureC", "nodeId", "ns=2;s=Line1.Temp"))),
                Map.of("endpointUrl", "opc.tcp://plc-1.internal:4840"),
                Map.of("certificate", "vault://sources/opcua-line-1/cert"),
                "eu-west-1",
                "ACTIVE",
                13L), opcUaId.toString());

        assertThat(runtimeConfigStore.get(modbusId)).hasValueSatisfying(config -> {
            assertThat(config.adapterType()).isEqualTo(AdapterType.GENERIC_MODBUS_TCP);
            assertThat(config.protocol()).isEqualTo(ProtocolType.MODBUS_TCP);
            assertThat(config.transport()).isEqualTo(TransportType.TCP);
            assertThat(config.mapping()).containsKey("registers");
            assertThat(config.adapterConfig()).containsEntry("host", "plc-1.internal");
            assertThat(config.credentialReferences()).containsEntry("basicAuth", "vault://sources/modbus-line-1/basic");
        });
        assertThat(runtimeConfigStore.get(opcUaId)).hasValueSatisfying(config -> {
            assertThat(config.adapterType()).isEqualTo(AdapterType.GENERIC_OPC_UA);
            assertThat(config.protocol()).isEqualTo(ProtocolType.OPC_UA);
            assertThat(config.transport()).isEqualTo(TransportType.TCP);
            assertThat(config.mapping().toString()).doesNotContain("password", "secret");
            assertThat(config.adapterConfig().toString()).doesNotContain("password", "secret");
        });
    }

    private SourceRuntimeConfigEvent event(UUID dataSourceId, String status, long configVersion) {
        return event(
                dataSourceId,
                "sim-fridge-001",
                "GENERIC_HTTP_PUSH",
                "REST",
                "HTTP",
                Map.of(),
                Map.of(),
                Map.of("ingestionToken", "vc_src_live_test"),
                "default",
                status,
                configVersion);
    }

    private SourceRuntimeConfigEvent event(
            UUID dataSourceId,
            String sourceId,
            String adapterType,
            String protocol,
            String transport,
            Map<String, Object> mapping,
            Map<String, Object> adapterConfig,
            Map<String, String> credentialReferences,
            String homeRegion,
            String status,
            long configVersion) {
        return new SourceRuntimeConfigEvent(
                UUID.randomUUID(),
                "v1",
                sourceId,
                dataSourceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                adapterType,
                protocol,
                transport,
                status,
                "v1",
                "v1",
                mapping,
                adapterConfig,
                credentialReferences,
                "vc_src_live_test",
                "token-hash",
                homeRegion,
                configVersion,
                Instant.now(),
                null,
                "device-service");
    }
}
