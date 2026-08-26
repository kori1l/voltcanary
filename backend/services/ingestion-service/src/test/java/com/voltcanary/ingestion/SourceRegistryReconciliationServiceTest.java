package com.voltcanary.ingestion;

import com.voltcanary.ingestion.config.SourceRegistryReconciliationProperties;
import com.voltcanary.ingestion.source.InternalDataSourceResponse;
import com.voltcanary.ingestion.source.SourceRegistryCache;
import com.voltcanary.ingestion.source.SourceRegistryReconciliationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRegistryReconciliationServiceTest {

    private final SourceRegistryCache cache = new SourceRegistryCache();
    private final SourceRegistryReconciliationProperties properties = new SourceRegistryReconciliationProperties();
    private final SourceRegistryReconciliationService service = new SourceRegistryReconciliationService(
            cache,
            properties,
            new SimpleMeterRegistry());

    @Test
    void countsLocalReadModelEntriesWithoutDeviceServiceRefresh() {
        UUID dataSourceId = UUID.randomUUID();
        InternalDataSourceResponse cached = source(dataSourceId, "ACTIVE");
        cache.put(cached, 1L);

        SourceRegistryReconciliationService.ReconciliationResult result = service.reconcileCachedSources();

        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(result.evicted()).isZero();
        assertThat(cache.getByTokenPrefix("vc_src_live_test")).contains(cached);
    }

    @Test
    void evictsMalformedLocalEntry() {
        InternalDataSourceResponse malformed = new InternalDataSourceResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "SIMULATOR",
                "REST",
                "ACTIVE",
                "vc_src_live_test",
                "token-hash");
        cache.put(malformed, 1L);

        SourceRegistryReconciliationService.ReconciliationResult result = service.reconcileCachedSources();

        assertThat(result.refreshed()).isZero();
        assertThat(result.evicted()).isEqualTo(1);
        assertThat(cache.getByTokenPrefix("vc_src_live_test")).isEmpty();
    }

    private InternalDataSourceResponse source(UUID dataSourceId, String status) {
        return new InternalDataSourceResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                dataSourceId,
                "sim-fridge-001",
                "SIMULATOR",
                "REST",
                status,
                "vc_src_live_test",
                "token-hash");
    }
}
