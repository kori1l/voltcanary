package com.voltcanary.ingestion;

import com.voltcanary.ingestion.config.SourceRegistryReconciliationProperties;
import com.voltcanary.ingestion.source.DeviceServiceSourceClient;
import com.voltcanary.ingestion.source.InternalDataSourceResponse;
import com.voltcanary.ingestion.source.SourceRegistryCache;
import com.voltcanary.ingestion.source.SourceRegistryReconciliationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceRegistryReconciliationServiceTest {

    private final SourceRegistryCache cache = new SourceRegistryCache();
    private final DeviceServiceSourceClient sourceClient = mock(DeviceServiceSourceClient.class);
    private final SourceRegistryReconciliationProperties properties = new SourceRegistryReconciliationProperties();
    private final SourceRegistryReconciliationService service = new SourceRegistryReconciliationService(
            cache,
            sourceClient,
            properties,
            new SimpleMeterRegistry());

    @Test
    void refreshesCachedSourceFromDeviceService() {
        UUID dataSourceId = UUID.randomUUID();
        InternalDataSourceResponse cached = source(dataSourceId, "ACTIVE");
        InternalDataSourceResponse refreshed = source(dataSourceId, "REVOKED");
        cache.putByTokenPrefix("vc_src_live_test", cached);
        when(sourceClient.findById(dataSourceId)).thenReturn(refreshed);

        SourceRegistryReconciliationService.ReconciliationResult result = service.reconcileCachedSources();

        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(result.evicted()).isZero();
        assertThat(cache.getByTokenPrefix("vc_src_live_test")).contains(refreshed);
    }

    @Test
    void evictsCachedSourceMissingFromDeviceService() {
        UUID dataSourceId = UUID.randomUUID();
        cache.putByTokenPrefix("vc_src_live_test", source(dataSourceId, "ACTIVE"));
        when(sourceClient.findById(dataSourceId))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

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
                "token-hash");
    }
}
