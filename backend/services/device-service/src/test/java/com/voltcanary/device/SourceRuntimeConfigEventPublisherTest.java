package com.voltcanary.device;

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
            new DeviceKafkaProperties());

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
