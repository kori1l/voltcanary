package com.voltcanary.ingestion;

import com.voltcanary.common.events.device.SourceRuntimeConfigEvent;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.source.SourceRegistryCache;
import com.voltcanary.ingestion.source.SourceRuntimeConfigStore;
import com.voltcanary.ingestion.source.SourceRuntimeConfigConsumer;
import java.time.Instant;
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

    private SourceRuntimeConfigEvent event(UUID dataSourceId, String status, long configVersion) {
        return new SourceRuntimeConfigEvent(
                UUID.randomUUID(),
                "v1",
                "sim-fridge-001",
                dataSourceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "GENERIC_HTTP_PUSH",
                "REST",
                "HTTP",
                status,
                "v1",
                "v1",
                Map.of(),
                Map.of(),
                Map.of("ingestionToken", "vc_src_live_test"),
                "vc_src_live_test",
                "token-hash",
                "default",
                configVersion,
                Instant.now(),
                null,
                "device-service");
    }
}
