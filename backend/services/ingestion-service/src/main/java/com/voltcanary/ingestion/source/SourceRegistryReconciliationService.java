package com.voltcanary.ingestion.source;

import com.voltcanary.ingestion.config.SourceRegistryReconciliationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SourceRegistryReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistryReconciliationService.class);

    private final SourceRegistryCache cache;
    private final SourceRegistryReconciliationProperties properties;
    private final MeterRegistry meterRegistry;

    public SourceRegistryReconciliationService(
            SourceRegistryCache cache,
            SourceRegistryReconciliationProperties properties,
            MeterRegistry meterRegistry) {
        this.cache = cache;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "#{@sourceRegistryReconciliationProperties.interval.toMillis()}")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }
        ReconciliationResult result = reconcileCachedSources();
        if (result.total() > 0) {
            log.info(
                    "sourceRegistryReconciliation refreshed={} evicted={} failed={} cacheSize={}",
                    result.refreshed(),
                    result.evicted(),
                    result.failed(),
                    cache.size());
        }
    }

    public ReconciliationResult reconcileCachedSources() {
        int refreshed = 0;
        int evicted = 0;
        int failed = 0;
        for (SourceRegistryCache.CachedSource cached : cache.snapshot(properties.getBatchSize())) {
            if (cached.source().dataSourceId() == null || cached.source().externalId() == null) {
                cache.evictByTokenPrefix(cached.tokenPrefix());
                evicted++;
            } else {
                refreshed++;
            }
        }
        record("refreshed", refreshed);
        record("evicted", evicted);
        record("failed", failed);
        return new ReconciliationResult(refreshed, evicted, failed);
    }

    private void record(String outcome, int count) {
        if (count > 0) {
            meterRegistry.counter("ingestion.source_registry.reconciled", "outcome", outcome).increment(count);
        }
    }

    public record ReconciliationResult(int refreshed, int evicted, int failed) {

        int total() {
            return refreshed + evicted + failed;
        }
    }
}
