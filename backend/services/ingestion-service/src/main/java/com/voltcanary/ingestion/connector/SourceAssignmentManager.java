package com.voltcanary.ingestion.connector;

import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.IngestionAdapterResolver;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.source.SourceRuntimeConfigStore;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SourceAssignmentManager {

    private final SourceRuntimeConfigStore configStore;
    private final IngestionAdapterResolver adapterResolver;

    public SourceAssignmentManager(SourceRuntimeConfigStore configStore, IngestionAdapterResolver adapterResolver) {
        this.configStore = configStore;
        this.adapterResolver = adapterResolver;
    }

    public List<SourceRuntimeConfig> assignedSources() {
        return configStore.snapshot().stream()
                .filter(SourceRuntimeConfig::active)
                .filter(this::hasActiveConnectorAdapter)
                .toList();
    }

    private boolean hasActiveConnectorAdapter(SourceRuntimeConfig config) {
        return adapterResolver.resolve(config)
                .map(adapter -> adapter.manifest().supports(AdapterCapability.POLLING)
                        || adapter.manifest().supports(AdapterCapability.SUBSCRIPTION))
                .orElse(false);
    }
}
