package com.voltcanary.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import com.voltcanary.device.config.DeviceKafkaProperties;
import com.voltcanary.device.domain.DataSource;
import com.voltcanary.device.domain.DataSourceProtocol;
import com.voltcanary.device.domain.DataSourceType;
import com.voltcanary.device.domain.SourceStatus;
import com.voltcanary.device.event.DeviceOutboxService;
import com.voltcanary.device.event.SourceRuntimeConfigEventPublisher;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SourceRuntimeConfigEventPublisherTest {

    private final DeviceOutboxService outboxService = mock(DeviceOutboxService.class);
    private final SourceRuntimeConfigEventPublisher publisher = new SourceRuntimeConfigEventPublisher(
            outboxService,
            new DeviceKafkaProperties(),
            new ObjectMapper());

    @Test
    void publishesSourceRuntimeConfigWithoutRawToken() {
        UUID accountId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        DataSource source = source(dataSourceId);

        publisher.publish(accountId, source);

        ArgumentCaptor<SourceRuntimeConfigEvent> eventCaptor =
                ArgumentCaptor.forClass(SourceRuntimeConfigEvent.class);
        verify(outboxService).enqueue(
                anyString(),
                eq(SourceRuntimeConfigEventPublisher.TOPIC),
                eq(dataSourceId.toString()),
                eventCaptor.capture());
        SourceRuntimeConfigEvent event = eventCaptor.getValue();
        assertThat(event.sourceId()).isEqualTo("sim-fridge-001");
        assertThat(event.dataSourceId()).isEqualTo(dataSourceId);
        assertThat(event.adapterType()).isEqualTo("GENERIC_HTTP_PUSH");
        assertThat(event.protocol()).isEqualTo("REST");
        assertThat(event.transport()).isEqualTo("HTTP");
        assertThat(event.credentialReferences()).containsEntry("ingestionToken", "credential-ref");
        assertThat(event.tokenHash()).isEqualTo("token-hash");
        assertThat(event.configVersion()).isEqualTo(1787739330000000000L);
    }

    @Test
    void publishesModbusTcpRuntimeConfig() {
        UUID accountId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        DataSource source = source(dataSourceId);
        source.setProtocol(DataSourceProtocol.MODBUS_TCP);
        source.setMappingJson("""
                {"registers":[{"metric":"voltageV","address":0,"function":"HOLDING_REGISTER"}]}
                """);
        source.setAdapterConfigJson("""
                {"host":"plc-1.internal","port":502,"unitId":1}
                """);
        source.setCredentialReferencesJson("""
                {"basicAuth":"vault://sources/modbus-1/basic"}
                """);
        source.setHomeRegion("eu-west-1");

        publisher.publish(accountId, source);

        SourceRuntimeConfigEvent event = publishedEvent(dataSourceId);
        assertThat(event.adapterType()).isEqualTo("GENERIC_MODBUS_TCP");
        assertThat(event.protocol()).isEqualTo("MODBUS_TCP");
        assertThat(event.transport()).isEqualTo("TCP");
        assertThat(event.mapping()).containsKey("registers");
        assertThat(event.adapterConfig()).containsEntry("host", "plc-1.internal");
        assertThat(event.credentialReferences()).containsEntry("basicAuth", "vault://sources/modbus-1/basic");
        assertThat(event.homeRegion()).isEqualTo("eu-west-1");
        assertThat(event.adapterConfig()).doesNotContainKeys("password", "secret", "token");
    }

    @Test
    void publishesOpcUaRuntimeConfig() {
        UUID accountId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();
        DataSource source = source(dataSourceId);
        source.setProtocol(DataSourceProtocol.OPC_UA);
        source.setMappingJson("""
                {"nodes":[{"metric":"temperatureC","nodeId":"ns=2;s=Line1.Temp"}]}
                """);
        source.setAdapterConfigJson("""
                {"endpointUrl":"opc.tcp://plc-1.internal:4840","securityPolicy":"BASIC256SHA256"}
                """);
        source.setCredentialReferencesJson("""
                {"certificate":"vault://sources/opcua-1/cert"}
                """);

        publisher.publish(accountId, source);

        SourceRuntimeConfigEvent event = publishedEvent(dataSourceId);
        assertThat(event.adapterType()).isEqualTo("GENERIC_OPC_UA");
        assertThat(event.protocol()).isEqualTo("OPC_UA");
        assertThat(event.transport()).isEqualTo("TCP");
        assertThat(event.mapping()).containsKey("nodes");
        assertThat(event.adapterConfig()).containsEntry("endpointUrl", "opc.tcp://plc-1.internal:4840");
        assertThat(event.credentialReferences()).containsEntry("certificate", "vault://sources/opcua-1/cert");
        assertThat(event.mapping().toString()).doesNotContain("password", "secret");
        assertThat(event.adapterConfig().toString()).doesNotContain("password", "secret");
    }

    private SourceRuntimeConfigEvent publishedEvent(UUID dataSourceId) {
        ArgumentCaptor<SourceRuntimeConfigEvent> eventCaptor =
                ArgumentCaptor.forClass(SourceRuntimeConfigEvent.class);
        verify(outboxService).enqueue(
                anyString(),
                eq(SourceRuntimeConfigEventPublisher.TOPIC),
                eq(dataSourceId.toString()),
                eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private DataSource source(UUID dataSourceId) {
        DataSource source = new DataSource();
        source.setId(dataSourceId);
        source.setDeviceId(UUID.randomUUID());
        source.setName("sim-fridge-001");
        source.setType(DataSourceType.SMART_PLUG);
        source.setSourceId("sim-fridge-001");
        source.setSourceType(DataSourceType.SMART_PLUG);
        source.setProtocol(DataSourceProtocol.REST);
        source.setEnabled(true);
        source.setStatus(SourceStatus.ACTIVE);
        source.setTokenPrefix("vc_src_live_test");
        source.setIngestionTokenHash("token-hash");
        source.setCredentialsRef("credential-ref");
        source.setCreatedAt(Instant.now());
        source.setUpdatedAt(Instant.parse("2026-08-26T10:15:30Z"));
        return source;
    }
}
