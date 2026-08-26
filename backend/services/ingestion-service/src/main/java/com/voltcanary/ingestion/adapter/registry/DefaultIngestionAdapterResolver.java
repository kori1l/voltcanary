package com.voltcanary.ingestion.adapter.registry;

import com.voltcanary.ingestion.adapter.api.IngestionAdapterRegistry;
import com.voltcanary.ingestion.adapter.api.IngestionAdapterResolver;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TelemetryAdapter;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DefaultIngestionAdapterResolver implements IngestionAdapterResolver {

    private final IngestionAdapterRegistry registry;

    public DefaultIngestionAdapterResolver(IngestionAdapterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<TelemetryAdapter> resolve(SourceRuntimeConfig sourceRuntimeConfig) {
        return registry.find(
                sourceRuntimeConfig.adapterType(),
                sourceRuntimeConfig.protocol(),
                sourceRuntimeConfig.transport());
    }
}
