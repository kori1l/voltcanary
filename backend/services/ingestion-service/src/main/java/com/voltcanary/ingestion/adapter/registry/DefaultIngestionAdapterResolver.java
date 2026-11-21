package com.voltcanary.ingestion.adapter.registry;

import com.voltcanary.ingestion.adapter.api.IngestionAdapterRegistry;
import com.voltcanary.ingestion.adapter.api.IngestionAdapterResolver;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TelemetryAdapter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultIngestionAdapterResolver implements IngestionAdapterResolver {

    private final IngestionAdapterRegistry registry;

    @Override
    public Optional<TelemetryAdapter> resolve(SourceRuntimeConfig sourceRuntimeConfig) {
        return registry.find(
                sourceRuntimeConfig.adapterType(),
                sourceRuntimeConfig.protocol(),
                sourceRuntimeConfig.transport());
    }
}
